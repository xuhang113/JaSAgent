package dev.jasagent.gateway.store;

import dev.jasagent.api.exception.AgentException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 粘性库地址和账号按配置使用。口令只来自仓库外文件，失败说明里不出现口令。
 */
class AffinityJdbcTest {
    private static final String USER_PASSWORD = "gw015-user-secret";
    private static final String OTHER_URL = "jdbc:mysql://127.0.0.1:3306/jasagent_gw015"
            + "?connectTimeout=2000&socketTimeout=5000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED";

    /**
     * JDBC 地址里的 password= 会拒绝打开，说明里没有这段口令。
     */
    @Test
    void passwordInsideJdbcUrlIsRejected() {
        String secret = "url-secret-value";
        String url = "jdbc:mysql://127.0.0.1:3306/jasagent?password=" + secret + "&useSSL=false";
        AgentException failure = assertThrows(AgentException.class, () -> MysqlAffinityStore.open(url, "root", Path.of("missing-password-file")));
        assertFalse(String.valueOf(failure.getMessage()).contains(secret));
        assertFalse(failure.toString().contains(secret));
        assertFalse(String.valueOf(failure.getMessage()).contains("url-secret-value"));
    }

    /**
     * 口令文件缺失或为空时打开失败，说明里没有口令原文。
     */
    @Test
    void missingPasswordFileFailsWithoutTheSecret() throws Exception {
        Path missing = Path.of("target", "missing-urag-password");
        Files.deleteIfExists(missing);
        AgentException absent = assertThrows(AgentException.class, () -> MysqlAffinityStore.open(MysqlAffinityStore.DEFAULT_URL, "root", missing));
        assertFalse(String.valueOf(absent.getMessage()).contains(USER_PASSWORD));
        assertTrue(absent.getMessage().contains("没有找到本机 MySQL 口令"));
        Path empty = Files.createTempFile("gw015-empty", ".password");
        Files.writeString(empty, "\n");
        AgentException blank = assertThrows(AgentException.class, () -> MysqlAffinityStore.open(MysqlAffinityStore.DEFAULT_URL, "root", empty));
        assertFalse(String.valueOf(blank.getMessage()).contains(USER_PASSWORD));
        assertTrue(blank.getMessage().contains("没有找到本机 MySQL 口令"));
    }

    /**
     * 非回环主机写进地址后不会被改回 127.0.0.1。连不上时说明里也没有口令。
     */
    @Test
    void nonLoopbackHostIsKept() {
        String secret = "sentinel-secret-value";
        String url = "jdbc:mysql://192.0.2.1:3306/otherdb?connectTimeout=1000&socketTimeout=1000&useSSL=false";
        MysqlAffinityStore store = new MysqlAffinityStore(url, "gw015", secret);
        assertEquals(url, store.jdbcUrl());
        assertEquals("gw015", store.username());
        assertTrue(store.toString().contains("192.0.2.1:3306/otherdb"));
        assertFalse(store.toString().contains(secret));
        AgentException failure = assertThrows(AgentException.class, () -> store.find("tenant", "ada"));
        assertFalse(String.valueOf(failure.getMessage()).contains(secret));
        assertFalse(failure.toString().contains(secret));
        assertFalse(store.toString().contains("127.0.0.1:3306/jasagent"));
    }

    /**
     * 配到另一个库名和另一个账号后，读写发生在那个库，而不是默认的 jasagent 库。
     */
    @Test
    void configuredDatabaseAndUserAreUsed() throws Exception {
        String root = UragPassword.read();
        assertFalse(root.isBlank(), "没有找到本机 MySQL 口令");
        prepare(root);
        Path file = Files.createTempFile("gw015-user", ".password");
        Files.writeString(file, USER_PASSWORD + "\n");
        MysqlAffinityStore store = MysqlAffinityStore.open(OTHER_URL, "gw015", file);
        assertEquals(OTHER_URL, store.jdbcUrl());
        assertEquals("gw015", store.username());
        assertTrue(store.toString().contains("127.0.0.1:3306/jasagent_gw015"));
        assertFalse(store.toString().contains(USER_PASSWORD));
        assertFalse(store.toString().contains(root));
        String tenant = "gw015-" + UUID.randomUUID();
        try {
            store.save(tenant, "ada", "agent-18732");
            assertEquals("agent-18732", store.find(tenant, "ada").orElseThrow());
            assertAbsentFromDefaultDatabase(tenant, root);
        } finally {
            store.deleteTenant(tenant);
        }
    }

    /**
     * 用根口令准备另一个库和专用账号。根口令不写进异常。
     *
     * @param root 仓库外文件里的根口令
     */
    private static void prepare(String root) throws Exception {
        try (Connection connection = DriverManager.getConnection(MysqlAffinityStore.DEFAULT_URL, "root", root);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS jasagent_gw015 CHARACTER SET utf8mb4");
            statement.execute("CREATE USER IF NOT EXISTS 'gw015'@'127.0.0.1' IDENTIFIED BY '" + USER_PASSWORD + "'");
            statement.execute("ALTER USER 'gw015'@'127.0.0.1' IDENTIFIED BY '" + USER_PASSWORD + "'");
            statement.execute("GRANT ALL PRIVILEGES ON jasagent_gw015.* TO 'gw015'@'127.0.0.1'");
        } catch (SQLException ex) {
            refuse(ex, root);
        }
    }

    /**
     * 默认库里不应出现写到另一个库的测试行。
     *
     * @param tenant 测试租户
     * @param root 根口令，只用于连接
     */
    private static void assertAbsentFromDefaultDatabase(String tenant, String root) throws Exception {
        try (Connection connection = DriverManager.getConnection(MysqlAffinityStore.DEFAULT_URL, "root", root);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM jasagent_gateway_affinity WHERE tenant_id = ?")) {
            statement.setString(1, tenant);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertEquals(0, rows.getInt(1));
            }
        } catch (SQLException ex) {
            String text = String.valueOf(ex.getMessage());
            if (text.contains("doesn't exist") || text.contains("does not exist")) {
                return;
            }
            refuse(ex, root);
        }
    }

    /**
     * 数据库失败不能把口令带进测试说明。
     *
     * @param failure 驱动抛出的异常
     * @param secret 不能出现的口令
     */
    private static void refuse(SQLException failure, String secret) {
        if (String.valueOf(failure.getMessage()).contains(secret) || failure.toString().contains(secret)) {
            throw new AssertionError("失败说明包含口令");
        }
        throw new AssertionError("粘性库准备失败");
    }
}
