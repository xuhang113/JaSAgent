package dev.jasagent.persist;

import dev.jasagent.api.exception.AgentException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件和会话在同一个 jasagent 库。另一条连接、另一个账本都能读到同一份字节。
 */
class SessionAttachmentLedgerTest {
    /**
     * 展示名去掉目录。对象路径不使用这个名字。
     */
    @Test
    void displayNameDropsDirectories() {
        assertEquals("holiday.png", SessionAttachmentLedger.displayName("../../holiday.png"));
        assertEquals("c.png", SessionAttachmentLedger.displayName("a\\b\\c.png"));
        assertEquals("", SessionAttachmentLedger.displayName(".."));
        assertEquals("", SessionAttachmentLedger.displayName("."));
    }

    /**
     * 写入后换一条连接读取。路径以租户和会话为前缀，迁租户后旧租户读不到。
     */
    @Test
    void bytesStayInJasagentAndFollowTheSessionTenant() throws Exception {
        UragMysql mysql = UragMysql.discover();
        assertTrue(mysql != null, "没有找到本机 MySQL 口令");
        assertFalse(mysql.url().toLowerCase().contains("password="));
        String user = "att" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String other = "att" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sessionId = "s" + UUID.randomUUID().toString().replace("-", "");
        String nextTenant = "t" + UUID.randomUUID().toString().replace("-", "");
        byte[] content = "png-bytes-026".getBytes(StandardCharsets.UTF_8);
        Path workspace = Files.createTempDirectory("att026");
        try (ExecutionDatabase database = open(workspace, mysql)) {
            SessionAttachmentLedger ledger = SessionAttachmentLedger.open(database);
            assertThrows(AgentException.class, () -> ledger.insert("local", "../x", user, "image/png", "a.png", content));
            SessionAttachmentLedger.Stored stored = ledger.insert(
                    "local", sessionId, user, "image/png", "../../holiday.png", content);
            assertEquals("holiday.png", stored.displayName());
            assertTrue(stored.objectPath().startsWith("tenant/local/session/" + sessionId + "/attachment/"));
            assertFalse(stored.objectPath().contains("holiday"));
            assertFalse(stored.objectPath().contains(".."));
            assertFalse(stored.toString().contains("holiday"));
            assertFalse(stored.toString().contains("png-bytes"));
            assertTrue(ledger.find("local", sessionId, other, stored.attachmentId()).isEmpty());
            assertTrue(ledger.list("local", sessionId, other).isEmpty());
            assertFalse(ledger.delete("local", sessionId, other, stored.attachmentId()));
            try (Connection connection = DriverManager.getConnection(mysql.url(), mysql.user(), mysql.password())) {
                try (Statement databaseName = connection.createStatement();
                     ResultSet name = databaseName.executeQuery("SELECT DATABASE()")) {
                    assertTrue(name.next());
                    assertEquals("jasagent", name.getString(1));
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT content, object_path FROM session_attachment
                        WHERE tenant_id = ? AND session_id = ? AND user_id = ? AND attachment_id = ?
                        """)) {
                    statement.setString(1, "local");
                    statement.setString(2, sessionId);
                    statement.setString(3, user);
                    statement.setString(4, stored.attachmentId());
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        assertArrayEquals(content, rows.getBytes(1));
                        assertEquals(stored.objectPath(), rows.getString(2));
                    }
                }
            }
            SessionLedger sessions = SessionLedger.open(database);
            sessions.moveUser("local", user, nextTenant);
            assertTrue(ledger.find("local", sessionId, user, stored.attachmentId()).isEmpty());
            SessionAttachmentLedger.Stored moved = ledger.find(nextTenant, sessionId, user, stored.attachmentId()).orElseThrow();
            assertArrayEquals(content, moved.content());
        }
        try (ExecutionDatabase again = open(workspace, mysql)) {
            SessionAttachmentLedger ledger = SessionAttachmentLedger.open(again);
            SessionAttachmentLedger.Stored reopened = ledger.find(nextTenant, sessionId, user, attachmentId(again, nextTenant, sessionId, user)).orElseThrow();
            assertArrayEquals(content, reopened.content());
            assertTrue(ledger.delete(nextTenant, sessionId, user, reopened.attachmentId()));
            assertTrue(ledger.find(nextTenant, sessionId, user, reopened.attachmentId()).isEmpty());
        } finally {
            deleteRows(mysql, sessionId);
        }
    }

    /**
     * 打开和网关相同的 jasagent 连接。
     *
     * @param workspace 临时目录
     * @param mysql 本机库
     * @return 执行库
     */
    private static ExecutionDatabase open(Path workspace, UragMysql mysql) {
        return ExecutionDatabase.open(workspace, Map.of(
                "JASAGENT_JDBC_URL", mysql.url(),
                "JASAGENT_JDBC_USER", mysql.user(),
                "JASAGENT_JDBC_PASSWORD", mysql.password()));
    }

    /**
     * 读出这次写入的附件编号。只按租户、会话和用户查找。
     *
     * @param database 执行库
     * @param tenantId 租户
     * @param sessionId 会话
     * @param userId 用户
     * @return 附件编号
     */
    private static String attachmentId(ExecutionDatabase database, String tenantId, String sessionId, String userId) {
        return SessionAttachmentLedger.open(database).list(tenantId, sessionId, userId).get(0).attachmentId();
    }

    /**
     * 删掉这次测试写入的附件和会话。失败时不把口令写进说明。
     *
     * @param mysql 本机库
     * @param sessionId 会话编号
     */
    private static void deleteRows(UragMysql mysql, String sessionId) {
        try (Connection connection = DriverManager.getConnection(mysql.url(), mysql.user(), mysql.password())) {
            delete(connection, "DELETE FROM session_attachment WHERE session_id = ?", sessionId);
            delete(connection, "DELETE FROM agent_session WHERE session_id = ?", sessionId);
        } catch (Exception ex) {
            throw new IllegalStateException("测试附件无法清理");
        }
    }

    /**
     * 按会话编号删除。表还不存在时忽略。
     *
     * @param connection 连接
     * @param sql 删除语句
     * @param sessionId 会话编号
     */
    private static void delete(Connection connection, String sql, String sessionId) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        } catch (Exception ex) {
            String text = String.valueOf(ex.getMessage());
            if (text.contains("doesn't exist") || text.contains("does not exist")) {
                return;
            }
            throw new IllegalStateException("测试附件无法清理");
        }
    }
}
