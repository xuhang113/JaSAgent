package dev.jasagent.gateway.cluster;

import dev.jasagent.gateway.agent.AgentDiscovery;
import dev.jasagent.gateway.agent.AgentInstance;
import dev.jasagent.gateway.agent.InMemoryAgentLiveness;
import dev.jasagent.gateway.store.AffinityStore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 组装 GET /api/cluster 的正文。每台 agent 一行，字段给控制台原样显示。
 * 行来自当时的 Nacos 健康实例。Nacos 不可用或为空时列表为空，不补静态约定地址。
 * online 和 connectionAlive 都表示到这台 agent 的长连接是否还在。断开就是下线。
 * stickyUsers 是粘性库里仍指向这台实例的登录名。lastHeartbeat 是最近一次在长连接上确认存活的时间。
 * 登录会话和存活探测都在网关进程内，本轮网关按单副本验收。
 */
public final class ClusterInventory {
    /**
     * 心跳时间的格式，带时区偏移，例如 2026-11-12T13:00:00+08:00。
     */
    public static final DateTimeFormatter HEARTBEAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final AgentDiscovery discovery;
    private final InMemoryAgentLiveness liveness;
    private final AffinityStore store;

    /**
     * 绑定发现、长连接状态和粘性库。
     *
     * @param discovery 当时的 Nacos 健康实例
     * @param liveness 长连接是否还在，只在本进程
     * @param store 用户到实例的粘性
     */
    public ClusterInventory(AgentDiscovery discovery, InMemoryAgentLiveness liveness, AffinityStore store) {
        this.discovery = discovery;
        this.liveness = liveness;
        this.store = store;
    }

    /**
     * 生成一个租户的集群正文。没有人粘着时 stickyUsers 是空数组，从未连上时 lastHeartbeat 是空串。
     * 缺省不算在线：连接不在时 online 和 connectionAlive 都是假。
     *
     * @param tenantId 只统计这个租户的粘性用户
     * @return 含 agents 数组的映射
     */
    public Map<String, Object> snapshot(String tenantId) {
        List<String> tenants = tenantId == null || tenantId.isBlank() ? List.of() : List.of(tenantId);
        return snapshotAll(tenants);
    }

    /**
     * 把多个租户的粘性用户并到同一份集群清单。admin 看全集群时用它。
     * 空白或超过 64 字符的租户跳过，避免粘性库拒绝整次查询。同一实例上的登录名按出现顺序去重。
     *
     * @param tenantIds 要合并的租户；为空时粘性用户都是空数组
     * @return 含 agents 数组的映射
     */
    public Map<String, Object> snapshotAll(Collection<String> tenantIds) {
        Map<String, LinkedHashSet<String>> users = new LinkedHashMap<>();
        if (tenantIds != null) {
            for (String tenantId : tenantIds) {
                if (tenantId == null || tenantId.isBlank() || tenantId.length() > 64) {
                    continue;
                }
                for (AffinityStore.AffinityRow row : store.list(tenantId)) {
                    users.computeIfAbsent(row.instanceId(), ignored -> new LinkedHashSet<>()).add(row.userId());
                }
            }
        }
        List<AgentInstance> catalog;
        try {
            List<AgentInstance> found = discovery.healthyNow();
            catalog = found == null ? List.of() : List.copyOf(found);
        } catch (RuntimeException ex) {
            catalog = List.of();
        }
        List<Map<String, Object>> agents = new ArrayList<>();
        for (AgentInstance instance : catalog) {
            boolean alive = liveness.connectionAlive(instance);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("instanceId", instance.instanceId());
            row.put("address", instance.address());
            row.put("online", alive);
            row.put("connectionAlive", alive);
            row.put("stickyUsers", List.copyOf(users.getOrDefault(instance.instanceId(), new LinkedHashSet<>())));
            row.put("lastHeartbeat", liveness.lastHeartbeat(instance).map(ClusterInventory::format).orElse(""));
            agents.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agents", agents);
        return body;
    }

    /**
     * 把时刻格式化成本地时区的秒级文本。
     *
     * @param instant 心跳时刻
     * @return 带偏移的时间文本
     */
    static String format(Instant instant) {
        return HEARTBEAT.format(instant.atZone(ZoneId.systemDefault()));
    }
}
