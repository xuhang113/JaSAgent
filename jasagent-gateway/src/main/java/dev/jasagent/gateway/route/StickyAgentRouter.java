package dev.jasagent.gateway.route;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.gateway.agent.AgentDiscovery;
import dev.jasagent.gateway.agent.AgentInstance;
import dev.jasagent.gateway.agent.InMemoryAgentLiveness;
import dev.jasagent.gateway.store.AffinityStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 按用户把请求粘到同一台仍可用的 agent。候选只来自当时 Nacos 查到的健康实例，再叠加本进程里的长连接存活。
 * 目标还在发现结果里且本进程认为存活时不改道。目标消失或不健康时改到其它实例并写回 MySQL。
 * 没有可用实例时保留原粘性，不删行。登录会话和存活探测都在网关进程内，本轮网关按单副本验收。
 */
public final class StickyAgentRouter {
    private final AgentDiscovery discovery;
    private final InMemoryAgentLiveness liveness;
    private final AffinityStore store;

    /**
     * 绑定发现、本进程存活表和粘性库。发现失败或为空时不能改用静态目录。
     *
     * @param discovery 当时的 Nacos 健康实例
     * @param liveness 每台 agent 的长连接状态，只存在本进程
     * @param store 粘性库，重启后仍能读到
     */
    public StickyAgentRouter(AgentDiscovery discovery, InMemoryAgentLiveness liveness, AffinityStore store) {
        this.discovery = discovery;
        this.liveness = liveness;
        this.store = store;
    }

    /**
     * 为这个用户选择一台 agent。已有粘性且该实例仍在健康发现结果里、长连接也还在时沿用。
     * 目标消失或不健康时，改选当前可用实例里端口最小的一台并写库。一台都没有时抛出，不删除已有粘性。
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
            AgentInstance current = find(alive, saved.get());
            if (current != null) {
                return new RouteDecision(current, false);
            }
        }
        if (alive.isEmpty()) {
            throw new AgentException(ErrorCode.INTERNAL, "没有可用的 agent");
        }
        AgentInstance picked = alive.get(0);
        store.save(tenantId, userId, picked.instanceId());
        return new RouteDecision(picked, true);
    }

    /**
     * 这一次既能从 Nacos 看到、本进程长连接也还在的实例，按端口再按主机排序。
     * 发现失败时原样抛出，不换成静态地址。
     *
     * @return 可以转发的实例
     */
    private List<AgentInstance> alive() {
        List<AgentInstance> discovered;
        try {
            discovered = discovery.healthyNow();
        } catch (RuntimeException ex) {
            if (ex instanceof AgentException agent) {
                throw agent;
            }
            throw new AgentException(ErrorCode.INTERNAL, "Nacos 不可用");
        }
        if (discovered == null) {
            discovered = List.of();
        }
        List<AgentInstance> found = new ArrayList<>();
        for (AgentInstance instance : discovered) {
            if (instance != null && liveness.connectionAlive(instance)) {
                found.add(instance);
            }
        }
        found.sort(Comparator.comparingInt(AgentInstance::port).thenComparing(AgentInstance::host));
        return found;
    }

    /**
     * 在当前可用列表里按编号查找。不在列表中就是不能继续转发。
     *
     * @param alive 当前可用实例
     * @param instanceId 粘性库里的编号
     * @return 对得上的实例；没有时为空
     */
    private static AgentInstance find(List<AgentInstance> alive, String instanceId) {
        for (AgentInstance instance : alive) {
            if (instance.instanceId().equals(instanceId)) {
                return instance;
            }
        }
        return null;
    }
}
