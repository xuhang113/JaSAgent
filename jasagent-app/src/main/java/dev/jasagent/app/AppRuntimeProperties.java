package dev.jasagent.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent 进程的运行参数。平台工作线程上限只给验收把操作系统线程压低时使用。
 * 默认不替换 Tomcat 的线程池，也不把生产上的 {@code server.tomcat.threads.max} 改低。
 */
@ConfigurationProperties(prefix = "jasagent.app")
public class AppRuntimeProperties {
    /**
     * 验收时 Tomcat 操作系统平台工作线程的上限。小于 1 表示不替换 Tomcat 自己的线程池。
     * 这不是 {@code server.tomcat.threads.max}；开启虚拟线程时后者往往只是请求槽位。
     */
    private int tomcatPlatformWorkers;

    /**
     * 返回验收用的平台工作线程上限。
     *
     * @return 小于 1 表示沿用 Tomcat 自己的池；缺省是 0
     */
    public int getTomcatPlatformWorkers() {
        return tomcatPlatformWorkers;
    }

    /**
     * 写入验收用的平台工作线程上限。生产配置应保持 0。
     *
     * @param tomcatPlatformWorkers 平台工作线程数；小于 1 时不安装固定池
     */
    public void setTomcatPlatformWorkers(int tomcatPlatformWorkers) {
        this.tomcatPlatformWorkers = tomcatPlatformWorkers;
    }
}
