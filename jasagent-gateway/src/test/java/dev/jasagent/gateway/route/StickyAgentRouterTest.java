package dev.jasagent.gateway.route;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.gateway.agent.AgentDiscovery;
import dev.jasagent.gateway.agent.AgentInstance;
import dev.jasagent.gateway.agent.InMemoryAgentLiveness;
import dev.jasagent.gateway.store.MysqlAffinityStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 粘性路由的三条规则：同一用户一直送到存活的原实例，实例下线后改道，网关重启后粘性还在。
 * 粘性写在本机 jasagent 库。测试租户用完即删，不打印数据库口令。
 */
class StickyAgentRouterTest {
    private final List<String> tenants = new ArrayList<>();
    private MysqlAffinityStore store;

    /**
     * 删掉本类写下的测试租户。库没打开时什么也不做。
     */
    @AfterEach
    void cleanup() {
        if (store == null) {
            return;
        }
        for (String tenant : tenants) {
            store.deleteTenant(tenant);
        }
    }

    /**
     * 两台都存活且没有旧粘性时，用户落到端口较小的一台，之后仍是这一台。
     */
    @Test
    void sameUserStaysOnTheLiveInstance() {
        InMemoryAgentLiveness liveness = bothUp();
        StickyAgentRouter router = router(liveness);
        String tenant = tenant();
        AgentInstance first = router.route(tenant, "ada").instance();
        AgentInstance second = router.route(tenant, "ada").instance();
        assertEquals(AgentInstance.primary().instanceId(), first.instanceId());
        assertEquals(first.instanceId(), second.instanceId());
        assertEquals(first.instanceId(), store.find(tenant, "ada").orElseThrow());
    }

    /**
     * 目标实例的长连接断开后改到另一台存活实例，并留在新实例上。
     * 原实例恢复后也不把用户迁回去。两台都断开时保留最后一次粘性，不删行。
     */
    @Test
    void offlineInstanceReroutesToAnotherLiveAgent() {
        InMemoryAgentLiveness liveness = bothUp();
        StickyAgentRouter router = router(liveness);
        String tenant = tenant();
        AgentInstance first = router.route(tenant, "ada").instance();
        liveness.markDown(first);
        AgentInstance moved = router.route(tenant, "ada").instance();
        assertEquals(AgentInstance.secondary().instanceId(), moved.instanceId());
        assertEquals(moved.instanceId(), router.route(tenant, "ada").instance().instanceId());
        liveness.markUp(first);
        assertEquals(moved.instanceId(), router.route(tenant, "ada").instance().instanceId());
        liveness.markDown(first);
        liveness.markDown(moved);
        assertThrows(AgentException.class, () -> router.route(tenant, "ada"));
        assertEquals(moved.instanceId(), store.find(tenant, "ada").orElseThrow());
    }

    /**
     * 关掉原来的路由器和连接对象后，新的路由器从库里读回同一台仍存活的实例。
     */
    @Test
    void stickinessSurvivesGatewayRestart() {
        InMemoryAgentLiveness liveness = bothUp();
        StickyAgentRouter first = router(liveness);
        String tenant = tenant();
        AgentInstance chosen = first.route(tenant, "ada").instance();
        InMemoryAgentLiveness restartedLiveness = bothUp();
        MysqlAffinityStore reopened = MysqlAffinityStore.openDefault();
        StickyAgentRouter restarted = new StickyAgentRouter(catalog(AgentInstance.defaults()), restartedLiveness, reopened);
        assertEquals(chosen.instanceId(), restarted.route(tenant, "ada").instance().instanceId());
        assertFalse(restarted.route(tenant, "ada").reassigned());
        assertEquals(chosen.instanceId(), reopened.find(tenant, "ada").orElseThrow());
    }

    /**
     * 数据库失败说明和存储对象的文本都不能带上口令。这里用的是假口令，不是本机文件里的口令。
     */
    @Test
    void databaseFailureDoesNotContainPassword() {
        String password = "sentinel-secret-value";
        MysqlAffinityStore closed = new MysqlAffinityStore(
                "jdbc:mysql://127.0.0.1:1/jasagent?connectTimeout=1000&socketTimeout=1000",
                "root",
                password);
        AgentException failure = assertThrows(AgentException.class, () -> closed.find("tenant", "ada"));
        assertFalse(String.valueOf(failure.getMessage()).contains(password));
        assertFalse(closed.toString().contains(password));
        assertTrue(closed.toString().contains("127.0.0.1:3306/jasagent") || closed.toString().contains("MysqlAffinityStore"));
    }

    /**
     * 两台约定实例都标成连接还在。
     *
     * @return 可用于路由的存活表
     */
    private static InMemoryAgentLiveness bothUp() {
        InMemoryAgentLiveness liveness = new InMemoryAgentLiveness();
        liveness.markUp(AgentInstance.primary());
        liveness.markUp(AgentInstance.secondary());
        return liveness;
    }

    /**
     * 打开本机粘性库并做一个路由器。库打不开时测试失败，说明里没有口令。
     *
     * @param liveness 长连接状态
     * @return 使用本机 jasagent 库的路由器
     */
    private StickyAgentRouter router(InMemoryAgentLiveness liveness) {
        if (store == null) {
            store = MysqlAffinityStore.openDefault();
        }
        return new StickyAgentRouter(catalog(AgentInstance.defaults()), liveness, store);
    }

    /**
     * 测试夹具里的发现结果。这不是网关在配置空白时的回退。
     *
     * @param instances 这次查询要返回的实例
     * @return 固定发现
     */
    private static AgentDiscovery catalog(java.util.List<AgentInstance> instances) {
        return () -> instances;
    }

    /**
     * 生成只属于这次测试的租户 id，结束时删掉。
     *
     * @return 租户 id
     */
    private String tenant() {
        String tenant = "gwtest-" + UUID.randomUUID();
        tenants.add(tenant);
        return tenant;
    }
}
