package dev.jasagent.gateway.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 一台 agent 的长连接是否还在，以及最近一次确认它还活着的时间。
 * 生产实现按网关副本把状态写进已有的 jasagent 库，读取时合并各副本。
 * 进程内实现只给单进程测试用，不能当作多副本选路的依据。
 */
public interface AgentLiveness {
    /**
     * 同一条长连接上两次健康检查的间隔。共享存活把超过三倍该间隔、仍标着已连接的心跳视为过期。
     */
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    /**
     * 本副本到这台 agent 的长连接已经建立。
     *
     * @param instance 刚连上的 agent
     */
    public void markUp(AgentInstance instance);

    /**
     * 本副本到这台 agent 的长连接已经断开。最近一次心跳保留。
     * 共享实现只改本副本的连接标记，不擦掉其它副本的近期心跳。
     *
     * @param instance 断开的 agent
     */
    public void markDown(AgentInstance instance);

    /**
     * 记下本副本在这条长连接上最近一次成功往返的时间。
     *
     * @param instance 仍连着的 agent
     * @param when 确认时刻，为空时忽略
     */
    public void heartbeat(AgentInstance instance, Instant when);

    /**
     * 这台 agent 现在是否还有可用的长连接。
     * 共享实现在任一副本仍连着且心跳未过期时为真。
     *
     * @param instance 要查询的 agent
     * @return 还可以转发时为真
     */
    public boolean connectionAlive(AgentInstance instance);

    /**
     * 最近一次确认存活的时刻。从未记过心跳时为空。断开后仍保留上次的时刻。
     *
     * @param instance 要查询的 agent
     * @return 心跳时刻；没有时为空
     */
    public Optional<Instant> lastHeartbeat(AgentInstance instance);
}
