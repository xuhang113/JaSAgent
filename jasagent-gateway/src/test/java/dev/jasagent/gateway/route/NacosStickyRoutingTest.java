package dev.jasagent.gateway.route;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.gateway.agent.AgentDiscovery;
import dev.jasagent.gateway.agent.AgentInstance;
import dev.jasagent.gateway.agent.InMemoryAgentLiveness;
import dev.jasagent.gateway.store.AffinityStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 粘性跟着当时的发现结果走。实例消失时改派；一个都不剩时失败且不删行。
 */
class NacosStickyRoutingTest {
    /**
     * 绑定实例仍健康时继续打到它。它从发现结果消失后改派并更新粘性。
     */
    @Test
    void keepsBindingUntilTheInstanceDisappears() {
        AgentInstance first = new AgentInstance("agent-10-0-0-1-18731", "10.0.0.1", 18731);
        AgentInstance second = new AgentInstance("agent-10-0-0-2-18732", "10.0.0.2", 18732);
        MutableDiscovery discovery = new MutableDiscovery();
        discovery.set(List.of(first, second));
        InMemoryAgentLiveness liveness = up(first, second);
        MemoryAffinity store = new MemoryAffinity();
        StickyAgentRouter router = new StickyAgentRouter(discovery, liveness, store);
        assertEquals(first.instanceId(), router.route("tenant", "ada").instance().instanceId());
        assertEquals(first.instanceId(), router.route("tenant", "ada").instance().instanceId());
        assertFalse(router.route("tenant", "ada").reassigned());
        discovery.set(List.of(second));
        RouteDecision moved = router.route("tenant", "ada");
        assertTrue(moved.reassigned());
        assertEquals(second.instanceId(), moved.instance().instanceId());
        assertEquals(second.instanceId(), store.find("tenant", "ada").orElseThrow());
    }

    /**
     * 发现为空或 Nacos 不可用时请求失败，粘性行还在，也不会出现静态回环地址。
     */
    @Test
    void emptyOrDownDiscoveryKeepsTheStickyRow() {
        AgentInstance first = new AgentInstance("agent-10-0-0-1-18731", "10.0.0.1", 18731);
        MutableDiscovery discovery = new MutableDiscovery();
        discovery.set(List.of(first));
        InMemoryAgentLiveness liveness = up(first);
        MemoryAffinity store = new MemoryAffinity();
        StickyAgentRouter router = new StickyAgentRouter(discovery, liveness, store);
        router.route("tenant", "ada");
        int saves = store.saves;
        discovery.set(List.of());
        AgentException empty = assertThrows(AgentException.class, () -> router.route("tenant", "ada"));
        assertFalse(empty.getMessage().contains("127.0.0.1"));
        assertEquals(first.instanceId(), store.find("tenant", "ada").orElseThrow());
        assertEquals(saves, store.saves);
        discovery.fail(new AgentException(ErrorCode.INTERNAL, "Nacos 不可用"));
        AgentException down = assertThrows(AgentException.class, () -> router.route("tenant", "ada"));
        assertEquals("Nacos 不可用", down.getMessage());
        assertFalse(down.getMessage().contains("18732"));
        assertEquals(first.instanceId(), store.find("tenant", "ada").orElseThrow());
        assertEquals(saves, store.saves);
    }

    /**
     * 把这些实例标成进程内长连接还在。
     *
     * @param instances 要标活的实例
     * @return 存活表
     */
    private static InMemoryAgentLiveness up(AgentInstance... instances) {
        InMemoryAgentLiveness liveness = new InMemoryAgentLiveness();
        for (AgentInstance instance : instances) {
            liveness.markUp(instance);
        }
        return liveness;
    }

    /**
     * 测试里可替换的发现。失败时抛出，不返回静态目录。
     */
    private static final class MutableDiscovery implements AgentDiscovery {
        private List<AgentInstance> instances = List.of();
        private RuntimeException failure;

        /**
         * 换成这一次的健康实例。
         *
         * @param instances 健康实例
         */
        private void set(List<AgentInstance> instances) {
            this.failure = null;
            this.instances = List.copyOf(instances);
        }

        /**
         * 下一次查询直接失败。
         *
         * @param failure 失败
         */
        private void fail(RuntimeException failure) {
            this.failure = failure;
        }

        /**
         * 返回当前列表，或抛出预设失败。
         *
         * @return 健康实例
         */
        @Override
        public List<AgentInstance> healthyNow() {
            if (failure != null) {
                throw failure;
            }
            return instances;
        }
    }

    /**
     * 内存粘性。没有删除方法，测试靠行还在来确认没删。
     */
    private static final class MemoryAffinity implements AffinityStore {
        private final Map<String, String> rows = new LinkedHashMap<>();
        private int saves;

        /**
         * 读一行。
         *
         * @param tenantId 租户
         * @param userId 用户
         * @return 实例编号
         */
        @Override
        public Optional<String> find(String tenantId, String userId) {
            return Optional.ofNullable(rows.get(tenantId + "\n" + userId));
        }

        /**
         * 写一行。
         *
         * @param tenantId 租户
         * @param userId 用户
         * @param instanceId 实例
         */
        @Override
        public void save(String tenantId, String userId, String instanceId) {
            saves++;
            rows.put(tenantId + "\n" + userId, instanceId);
        }

        /**
         * 集群用的列表。这个测试不用。
         *
         * @param tenantId 租户
         * @return 空列表
         */
        @Override
        public List<AffinityRow> list(String tenantId) {
            return new ArrayList<>();
        }
    }
}
