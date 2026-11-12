package dev.jasagent.gateway.agent;

import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 为目录里的每台 agent 启动一条长连接。
 * 停止网关时只关掉这些套接字，不停止 18731 和 18732 上的 agent 进程。
 */
public final class AgentLinkSupervisor implements SmartLifecycle {
    private final List<TcpAgentLink> links = new ArrayList<>();
    private volatile boolean running;

    /**
     * 为每台实例准备一条长连接，此时尚未拨号。
     *
     * @param catalog 要维持连接的 agent
     * @param liveness 连接状态的存放处，路由和集群都读它
     */
    public AgentLinkSupervisor(List<AgentInstance> catalog, InMemoryAgentLiveness liveness) {
        for (AgentInstance instance : catalog) {
            links.add(new TcpAgentLink(instance, liveness, Duration.ofSeconds(2), Duration.ofSeconds(15)));
        }
    }

    /**
     * 开始拨号。每台 agent 一条后台连接。
     * 副作用：启动长连接线程。
     */
    @Override
    public void start() {
        for (TcpAgentLink link : links) {
            link.start();
        }
        running = true;
    }

    /**
     * 关掉网关这一端的长连接，把实例标成下线。不向 agent 发送停止命令。
     * 副作用：中断长连接线程。
     */
    @Override
    public void stop() {
        for (TcpAgentLink link : links) {
            link.stop();
        }
        running = false;
    }

    /**
     * 这些长连接是否已经启动。
     *
     * @return 已调用 {@link #start()} 且尚未 {@link #stop()} 时为真
     */
    @Override
    public boolean isRunning() {
        return running;
    }
}
