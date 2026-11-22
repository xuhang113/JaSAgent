package dev.jasagent.gateway.agent;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录本进程看到的长连接是否还在，以及最近一次在这条连接上确认它还活着的时间。
 * 连接断开只改这里的标记，不关闭对方进程。生产选路、集群页和长连接不读这份表，改读 {@link MysqlAgentLiveness}。
 * 登录会话在 MySQL。存活探测按网关副本写入同一库并跨副本读取。本机缺省副本数仍是 1。
 */
public final class InMemoryAgentLiveness implements AgentLiveness {
    private final Set<String> connected = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Instant> beats = new ConcurrentHashMap<>();

    /**
     * 长连接已经建立。之后 {@link #connectionAlive(AgentInstance)} 为真，直到 {@link #markDown(AgentInstance)}。
     *
     * @param instance 刚连上的 agent
     */
    @Override
    public void markUp(AgentInstance instance) {
        connected.add(instance.instanceId());
    }

    /**
     * 长连接已经断开。最近一次心跳时间保留，供集群页显示。
     *
     * @param instance 断开的 agent
     */
    @Override
    public void markDown(AgentInstance instance) {
        connected.remove(instance.instanceId());
    }

    /**
     * 记下这条长连接上最近一次成功往返的时间。
     *
     * @param instance 仍连着的 agent
     * @param when 确认时刻，不能为空
     */
    @Override
    public void heartbeat(AgentInstance instance, Instant when) {
        if (when != null) {
            beats.put(instance.instanceId(), when);
        }
    }

    /**
     * 这台 agent 的长连接现在是否还在。
     *
     * @param instance 要查询的 agent
     * @return 连接还在时为真
     */
    @Override
    public boolean connectionAlive(AgentInstance instance) {
        return connected.contains(instance.instanceId());
    }

    /**
     * 最近一次在长连接上确认存活的时刻。从未连上时为空。
     *
     * @param instance 要查询的 agent
     * @return 心跳时刻；没有时为空
     */
    @Override
    public Optional<Instant> lastHeartbeat(AgentInstance instance) {
        return Optional.ofNullable(beats.get(instance.instanceId()));
    }
}
