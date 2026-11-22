package dev.jasagent.gateway.agent;

import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 为本进程维护到已发现 agent 的长连接。连接目标只来自当时的 Nacos 健康实例。
 * 查询失败或列表为空时不拨到静态约定地址。停止网关时只关掉这些套接字。
 * 登录会话在 MySQL。存活探测按网关副本写入同一库并跨副本读取。本机缺省副本数仍是 1。
 */
public final class AgentLinkSupervisor implements SmartLifecycle {
    /**
     * 断开后再次连接前的等待。
     */
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);
    /**
     * 同一条连接上两次健康检查的间隔。与共享存活的新鲜度使用同一个间隔。
     */
    private static final Duration HEARTBEAT = AgentLiveness.HEARTBEAT_INTERVAL;

    private final AgentDiscovery discovery;
    private final AgentLiveness liveness;
    private final Object guard = new Object();
    private final Map<String, TcpAgentLink> links = new LinkedHashMap<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    /**
     * 绑定发现和本进程存活表。此时还不拨号。
     *
     * @param discovery 当时的健康实例
     * @param liveness 连接状态的存放处。生产按副本写入共享库，路由和集群读同一份
     */
    public AgentLinkSupervisor(AgentDiscovery discovery, AgentLiveness liveness) {
        this.discovery = discovery;
        this.liveness = liveness;
    }

    /**
     * 按当前发现结果拨号，并定期刷新。发现失败时不改用静态目录。
     * 副作用：启动长连接线程。
     */
    @Override
    public void start() {
        synchronized (guard) {
            if (running) {
                return;
            }
            running = true;
            refresh();
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "jasagent-nacos-links");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::refreshQuietly, 2, 2, TimeUnit.SECONDS);
        }
    }

    /**
     * 关掉网关这一端的长连接。不向 agent 发送停止命令，也不去停 Nacos。
     * 副作用：中断长连接线程。
     */
    @Override
    public void stop() {
        ScheduledExecutorService local;
        synchronized (guard) {
            running = false;
            local = scheduler;
            scheduler = null;
            for (TcpAgentLink link : links.values()) {
                link.stop();
            }
            links.clear();
        }
        if (local != null) {
            local.shutdownNow();
        }
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

    /**
     * 刷新时吞掉异常，避免调度线程停掉。不因此改用静态目录。
     */
    private void refreshQuietly() {
        try {
            synchronized (guard) {
                refresh();
            }
        } catch (RuntimeException ex) {
            // 发现失败时保持已有连接，不拨到静态地址。
        }
    }

    /**
     * 让长连接跟上这一次的健康实例。调用方必须已经持有 {@link #guard}。
     */
    private void refresh() {
        if (!running) {
            return;
        }
        List<AgentInstance> found;
        try {
            found = discovery.healthyNow();
        } catch (RuntimeException ex) {
            return;
        }
        if (found == null) {
            found = List.of();
        }
        Set<String> keep = new LinkedHashSet<>();
        for (AgentInstance instance : found) {
            if (instance == null) {
                continue;
            }
            keep.add(instance.instanceId());
            if (!links.containsKey(instance.instanceId())) {
                TcpAgentLink link = new TcpAgentLink(instance, liveness, RETRY_DELAY, HEARTBEAT);
                links.put(instance.instanceId(), link);
                link.start();
            }
        }
        Iterator<Map.Entry<String, TcpAgentLink>> iterator = links.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TcpAgentLink> entry = iterator.next();
            if (!keep.contains(entry.getKey())) {
                entry.getValue().stop();
                iterator.remove();
            }
        }
    }
}
