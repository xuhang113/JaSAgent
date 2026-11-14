package dev.jasagent.gateway;

import dev.jasagent.gateway.store.MysqlAffinityStore;
import dev.jasagent.gateway.store.UragPassword;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关进程按配置项打开粘性库。改了库名和账号后，网关用的就是这一份连接。
 */
@SpringBootTest(properties = "jasagent.gateway.affinity-wiring-test=true")
class GatewayAffinityWiringTest {
    private static final String USER_PASSWORD = "gw015-user-secret";
    private static final String OTHER_URL = "jdbc:mysql://127.0.0.1:3306/jasagent_gw015"
            + "?connectTimeout=2000&socketTimeout=5000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED";
    private static Path passwordFile;

    @Autowired
    private MysqlAffinityStore store;

    /**
     * 先把专用账号和口令文件准备好，再告诉网关用这个地址。
     *
     * @param registry 测试属性
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        String root = UragPassword.read();
        if (root.isBlank()) {
            throw new IllegalStateException("没有找到本机 MySQL 口令");
        }
        try (Connection connection = DriverManager.getConnection(MysqlAffinityStore.DEFAULT_URL, "root", root);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS jasagent_gw015 CHARACTER SET utf8mb4");
            statement.execute("CREATE USER IF NOT EXISTS 'gw015'@'127.0.0.1' IDENTIFIED BY '" + USER_PASSWORD + "'");
            statement.execute("ALTER USER 'gw015'@'127.0.0.1' IDENTIFIED BY '" + USER_PASSWORD + "'");
            statement.execute("GRANT ALL PRIVILEGES ON jasagent_gw015.* TO 'gw015'@'127.0.0.1'");
        } catch (SQLException ex) {
            if (String.valueOf(ex.getMessage()).contains(root)) {
                throw new IllegalStateException("失败说明包含口令");
            }
            throw new IllegalStateException("粘性库准备失败");
        }
        passwordFile = Files.createTempFile("gw015-wiring", ".password");
        Files.writeString(passwordFile, USER_PASSWORD + "\n");
        registry.add(MysqlAffinityStore.URL_PROPERTY, () -> OTHER_URL);
        registry.add(MysqlAffinityStore.USER_PROPERTY, () -> "gw015");
        registry.add(MysqlAffinityStore.PASSWORD_FILE_PROPERTY, () -> passwordFile.toString());
        registry.add("jasagent.gateway.links.enabled", () -> "false");
    }

    /**
     * 注入进来的粘性库使用配置的地址和账号，并能在那个库里写下再读回。
     */
    @Test
    void gatewayStoreUsesConfiguredAddress() {
        assertEquals(OTHER_URL, store.jdbcUrl());
        assertEquals("gw015", store.username());
        assertTrue(store.toString().contains("jasagent_gw015"));
        assertFalse(store.toString().contains(USER_PASSWORD));
        String tenant = "wire-" + UUID.randomUUID();
        try {
            store.save(tenant, "ada", "agent-18731");
            assertEquals("agent-18731", store.find(tenant, "ada").orElseThrow());
        } finally {
            store.deleteTenant(tenant);
        }
    }
}
