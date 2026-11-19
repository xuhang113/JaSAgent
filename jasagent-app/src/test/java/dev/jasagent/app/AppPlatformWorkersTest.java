package dev.jasagent.app;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台工作线程上限默认是 0，不替换 Tomcat 的池。只有验收把上限设成正数时才安装固定数量的操作系统线程。
 */
class AppPlatformWorkersTest {
    /**
     * 配置文件里的生产默认必须是 0，不能把平台线程上限写死成一个很小的数。
     */
    @Test
    void productionDefaultDoesNotCapPlatformWorkers() throws Exception {
        String text = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(text.contains("jasagent.app.tomcat-platform-workers=0"), text);
    }

    /**
     * 上限小于 1 时不往工厂里加连接器定制器。
     */
    @Test
    void zeroCapLeavesTomcatPoolAlone() {
        AppRuntimeProperties properties = new AppRuntimeProperties();
        properties.setTomcatPlatformWorkers(0);
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        new AppPlatformWorkerCustomizer(properties).customize(factory);
        assertTrue(factory.getTomcatConnectorCustomizers().isEmpty());
    }

    /**
     * 上限为 2 时安装的执行器核心数就是 2，线程名前缀是 agent 自己的平台线程名。
     */
    @Test
    void positiveCapInstallsThatManyPlatformThreads() {
        AppRuntimeProperties properties = new AppRuntimeProperties();
        properties.setTomcatPlatformWorkers(2);
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        new AppPlatformWorkerCustomizer(properties).customize(factory);
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        factory.getTomcatConnectorCustomizers().forEach(customizer -> customizer.customize(connector));
        Executor executor = ((AbstractProtocol<?>) connector.getProtocolHandler()).getExecutor();
        try {
            ThreadPoolExecutor pool = assertInstanceOf(ThreadPoolExecutor.class, executor);
            assertEquals(2, pool.getCorePoolSize());
            assertEquals(2, pool.getMaximumPoolSize());
            assertTrue(pool.getThreadFactory().newThread(() -> { }).getName().startsWith(AppPlatformWorkerCustomizer.THREAD_NAME_PREFIX));
        } finally {
            if (executor instanceof ThreadPoolExecutor pool) {
                pool.shutdownNow();
            }
        }
    }
}
