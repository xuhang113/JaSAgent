package dev.jasagent.sidecar;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.ProtocolHandler;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.TaskThreadFactory;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;

import java.util.concurrent.TimeUnit;

/**
 * 按配置安装一组固定数量的 Tomcat 平台工作线程。只在验收要把操作系统平台线程压低时启用。
 * 默认不安装，生产仍用 Tomcat 自己的线程池，不会把线程上限改低。
 * 这组线程是真正的平台线程，不是虚拟线程，也不是 {@code server.tomcat.threads.max} 在开启虚拟线程后代表的请求槽位。
 */
final class TomcatPlatformWorkerCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {
    /**
     * 平台工作线程的名字前缀。验收用它从线程转储里认出操作系统平台线程。
     */
    static final String THREAD_NAME_PREFIX = "jasagent-tomcat-platform-";
    /**
     * 空闲平台线程保留的秒数。数量已经固定，这个值只满足线程池构造。
     */
    private static final long KEEP_ALIVE_SECONDS = 60L;

    private final SidecarProperties properties;

    /**
     * 绑定运行参数。构造时不创建线程。
     *
     * @param properties 含平台工作线程上限的配置；小于 1 表示不改 Tomcat 的池
     */
    TomcatPlatformWorkerCustomizer(SidecarProperties properties) {
        this.properties = properties;
    }

    /**
     * 在连接器启动前换上固定大小的平台线程池，并预先把线程建出来，方便验收点数。
     * 上限小于 1 时什么都不做。
     *
     * @param factory 内嵌 Tomcat 工厂
     */
    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        int cap = properties.getTomcatPlatformWorkers();
        if (cap < 1) {
            return;
        }
        factory.addConnectorCustomizers(connector -> install(connector, cap));
    }

    /**
     * 让本定制器晚于 Spring Boot 自己的 Tomcat 定制器执行，避免后写的执行器把平台线程池换掉。
     *
     * @return 最低优先级
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * 用固定核心数和最大数的平台线程池替换连接器执行器。停止连接器时关掉它，避免测试里留下线程。
     *
     * @param connector 尚未启动的连接器
     * @param cap 平台工作线程数，至少为 1
     */
    private static void install(Connector connector, int cap) {
        ProtocolHandler handler = connector.getProtocolHandler();
        if (!(handler instanceof AbstractProtocol<?> protocol)) {
            return;
        }
        TaskQueue queue = new TaskQueue();
        TaskThreadFactory threads = new TaskThreadFactory(THREAD_NAME_PREFIX, true, Thread.NORM_PRIORITY);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(cap, cap, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, queue, threads);
        queue.setParent(executor);
        executor.prestartAllCoreThreads();
        protocol.setExecutor(executor);
        connector.addLifecycleListener(event -> {
            if (Lifecycle.AFTER_STOP_EVENT.equals(event.getType())) {
                executor.shutdownNow();
            }
        });
    }
}
