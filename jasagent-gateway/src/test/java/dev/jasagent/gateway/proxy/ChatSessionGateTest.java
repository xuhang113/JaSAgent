package dev.jasagent.gateway.proxy;

import dev.jasagent.persist.SessionLedger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转发前的会话归属判断。库里没有这场会话时不拒绝；有行但不是当前用户时拒绝。
 */
class ChatSessionGateTest {
    /**
     * 详情、消息、时间线和删除都带会话编号。列表和新建没有。
     */
    @Test
    void sessionPathsKeepTheId() {
        assertEquals("s1", ChatSessionGate.sessionId("/api/sessions/s1"));
        assertEquals("s1", ChatSessionGate.sessionId("/api/sessions/s1/messages"));
        assertEquals("s1", ChatSessionGate.sessionId("/api/sessions/s1/timeline"));
        assertTrue(ChatSessionGate.targetsSession("/api/sessions/s1/context"));
        assertNull(ChatSessionGate.sessionId("/api/sessions"));
        assertNull(ChatSessionGate.sessionId("/api/sessions/"));
        assertFalse(ChatSessionGate.targetsSession("/api/sessions"));
    }

    /**
     * 空结果不是他人的会话。只要有一行不属于当前租户和用户，就在转发前拒绝。
     */
    @Test
    void foreignRowsAreRejectedAndUnknownSessionsAreNot() {
        assertFalse(ChatSessionGate.foreign(List.of(), "t1", "ada"));
        SessionLedger.Row own = new SessionLedger.Row("t1", "s1", "ada", "", "", "", false, "", "");
        SessionLedger.Row other = new SessionLedger.Row("t2", "s1", "bao", "", "", "", false, "", "");
        assertFalse(ChatSessionGate.foreign(List.of(own), "t1", "ada"));
        assertTrue(ChatSessionGate.foreign(List.of(other), "t1", "ada"));
        assertTrue(ChatSessionGate.foreign(List.of(other, own), "t9", "ada"));
    }
}
