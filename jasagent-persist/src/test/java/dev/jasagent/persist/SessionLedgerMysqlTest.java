package dev.jasagent.persist;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天会话仍在 MySQL 的 agent_session。按用户隔离，迁租户只改这一份账本。
 */
class SessionLedgerMysqlTest {
    /**
     * 列表只含当前用户。按会话编号能跨租户找到行。moveUser 把 local 改到用户自己的租户，重新打开后还在。
     */
    @Test
    void ownershipStaysInMysqlAndFollowsTheUserTenant() throws Exception {
        UragMysql mysql = UragMysql.discover();
        assertTrue(mysql != null, "没有找到本机 MySQL 口令");
        assertTrue(mysql.url().startsWith("jdbc:mysql:"));
        assertFalse(mysql.url().toLowerCase().contains("password="));
        Path workspace = Files.createTempDirectory("ledger020");
        String user = "ledger" + UUID.randomUUID().toString().substring(0, 8);
        String other = "ledger" + UUID.randomUUID().toString().substring(0, 8);
        String ownId = "own-" + UUID.randomUUID();
        String otherId = "other-" + UUID.randomUUID();
        String now = Instant.now().toString();
        String nextTenant = "t" + UUID.randomUUID().toString().replace("-", "");
        try {
        try (ExecutionDatabase database = ExecutionDatabase.open(workspace, Map.of(
                "JASAGENT_JDBC_URL", mysql.url(),
                "JASAGENT_JDBC_USER", mysql.user(),
                "JASAGENT_JDBC_PASSWORD", mysql.password()))) {
            SessionLedger ledger = SessionLedger.open(database);
            ledger.save("local", ownId, user, "自己的", "m", "p", false, now, now, "{}");
            ledger.save("local", otherId, other, "别人的", "m", "p", false, now, now, "{}");
            List<String> visible = ledger.list("local", user).stream().map(SessionLedger.Row::sessionId).toList();
            assertTrue(visible.contains(ownId));
            assertFalse(visible.contains(otherId));
            assertEquals(user, ledger.findBySessionId(ownId).get(0).userId());
            ledger.moveUser("local", user, nextTenant);
            assertTrue(ledger.list("local", user).stream().noneMatch(row -> ownId.equals(row.sessionId())));
            assertEquals(nextTenant, ledger.findBySessionId(ownId).get(0).tenantId());
            assertEquals("local", ledger.findBySessionId(otherId).get(0).tenantId());
        }
        try (ExecutionDatabase again = ExecutionDatabase.open(workspace, Map.of(
                "JASAGENT_JDBC_URL", mysql.url(),
                "JASAGENT_JDBC_USER", mysql.user(),
                "JASAGENT_JDBC_PASSWORD", mysql.password()))) {
            SessionLedger ledger = SessionLedger.open(again);
            assertEquals(nextTenant, ledger.findBySessionId(ownId).get(0).tenantId());
            assertEquals(user, ledger.findBySessionId(ownId).get(0).userId());
        }
        } finally {
            deleteSession(mysql, ownId);
            deleteSession(mysql, otherId);
        }
    }

    /**
     * 删掉这次写入的会话行。失败时不把口令写进说明。
     *
     * @param mysql 本机库
     * @param sessionId 会话编号
     */
    private static void deleteSession(UragMysql mysql, String sessionId) {
        try (Connection connection = DriverManager.getConnection(mysql.url(), mysql.user(), mysql.password());
             PreparedStatement statement = connection.prepareStatement("DELETE FROM agent_session WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException("测试会话无法清理");
        }
    }
}
