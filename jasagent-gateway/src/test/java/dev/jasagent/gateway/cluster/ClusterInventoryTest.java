package dev.jasagent.gateway.cluster;

import dev.jasagent.gateway.agent.AgentInstance;
import dev.jasagent.gateway.agent.InMemoryAgentLiveness;
import dev.jasagent.gateway.store.AffinityStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 集群行的字段和缺省值。连接不在时不能显示成在线。
 */
class ClusterInventoryTest {
    /**
     * 存活实例带心跳和粘着用户；断开的实例两项在线标志都是假，心跳仍保留上次的文本。
     */
    @Test
    void snapshotUsesConnectionAndStickyUsers() {
        InMemoryAgentLiveness liveness = new InMemoryAgentLiveness();
        liveness.markUp(AgentInstance.primary());
        liveness.heartbeat(AgentInstance.primary(), Instant.parse("2026-11-12T05:00:00Z"));
        liveness.heartbeat(AgentInstance.secondary(), Instant.parse("2026-11-12T04:00:00Z"));
        AffinityStore store = new AffinityStore() {
            @Override
            public Optional<String> find(String tenantId, String userId) {
                return Optional.empty();
            }

            @Override
            public void save(String tenantId, String userId, String instanceId) {
            }

            @Override
            public List<AffinityRow> list(String tenantId) {
                return List.of(new AffinityRow("ada", "agent-18731"), new AffinityRow("bao", "agent-18732"));
            }
        };
        List<java.util.Map<String, Object>> agents = cast(new ClusterInventory(AgentInstance.defaults(), liveness, store).snapshot("local").get("agents"));
        java.util.Map<String, Object> primary = agents.get(0);
        java.util.Map<String, Object> secondary = agents.get(1);
        assertEquals("agent-18731", primary.get("instanceId"));
        assertEquals("127.0.0.1:18731", primary.get("address"));
        assertEquals(true, primary.get("online"));
        assertEquals(true, primary.get("connectionAlive"));
        assertEquals(List.of("ada"), primary.get("stickyUsers"));
        assertFalse(String.valueOf(primary.get("lastHeartbeat")).isBlank());
        assertEquals("agent-18732", secondary.get("instanceId"));
        assertEquals(false, secondary.get("online"));
        assertEquals(false, secondary.get("connectionAlive"));
        assertEquals(List.of("bao"), secondary.get("stickyUsers"));
        assertFalse(String.valueOf(secondary.get("lastHeartbeat")).isBlank());
    }

    /**
     * 把集群数组收成带类型的列表，避免测试里散落未经检查的转换。
     *
     * @param value snapshot 里的 agents
     * @return 行列表
     */
    @SuppressWarnings("unchecked")
    private static List<java.util.Map<String, Object>> cast(Object value) {
        return (List<java.util.Map<String, Object>>) value;
    }
}
