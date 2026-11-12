package dev.jasagent.gateway.route;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.gateway.agent.AgentInstance;
import dev.jasagent.gateway.agent.InMemoryAgentLiveness;
import dev.jasagent.gateway.store.AffinityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 按用户把请求粘到同一台仍存活的 agent。
 * 目标实例的长连接还在时不改道。连接断开后改到其它存活实例，并写回 MySQL。
 * 没有存活实例时保留原粘性，不删行，让实例恢复后仍能粘回去。
 */
public final class StickyAgentRouter {
    private final List<AgentInstance> catalog;
    private final InMemoryAgentLiveness liveness;
    private final AffinityStore store;

    /**
     * 绑定实例目录、长连接状态和粘性库。目录按端口升序解释，先选端口较小的存活实例。
     *
     * @param catalog 允许转发的 agent，空目录无法路由
     * @param liveness 每台 agent 的长连接状态
     * @param store 粘性库，重启后仍能读到
     */
    public StickyAgentRouter(List<AgentInstance> catalog, InMemoryAgentLiveness liveness, AffinityStore store) {
        this.catalog = List.copyOf(catalog);
        this.liveness = liveness;
        this.store = store;
    }

    /**
     * 为这个用户选择一台 agent。已有粘性且该实例仍存活时沿用，并返回 reassigned 为假。
     * 粘性缺失或目标已下线时，改选当前存活实例里端口最小的一台并写库。
     * 一台都不存活时抛出内部错误，不删除已有粘性。
     *
     * @param tenantId 租户 id
     * @param userId 用户 id，即登录名
     * @return 选中的实例，以及这次是否改写了粘性
     */
    public RouteDecision route(String tenantId, String userId) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            throw new AgentException(ErrorCode.INTERNAL, "粘性键无效");
        }
        List<AgentInstance> alive = alive();
        Optional<String> saved = store.find(tenantId, userId);
        if (saved.isPresent()) {
            AgentInstance current = find(saved.get());
            if (current != null && liveness.connectionAlive(current)) {
                return new RouteDecision(current, false);
            }
        }
        if (alive.isEmpty()) {
            throw new AgentException(ErrorCode.INTERNAL, "没有存活的 agent");
        }
        AgentInstance picked = alive.get(0);
        store.save(tenantId, userId, picked.instanceId());
        return new RouteDecision(picked, true);
    }

    /**
     * 当前存活的实例，按端口升序。
     *
     * @return 长连接还在的实例
     */
    private List<AgentInstance> alive() {
        List<AgentInstance> found = new ArrayList<>();
        for (AgentInstance instance : catalog) {
            if (liveness.connectionAlive(instance)) {
                found.add(instance);
            }
        }
        found.sort((left, right) -> Integer.compare(left.port(), right.port()));
        return found;
    }

    /**
     * 按实例编号在目录里查找。库里的编号不在目录中时当作已经不能转发。
     *
     * @param instanceId 粘性库里的编号
     * @return 目录中的实例；对不上时为空
     */
    private AgentInstance find(String instanceId) {
        for (AgentInstance instance : catalog) {
            if (instance.instanceId().equals(instanceId)) {
                return instance;
            }
        }
        return null;
    }
}
