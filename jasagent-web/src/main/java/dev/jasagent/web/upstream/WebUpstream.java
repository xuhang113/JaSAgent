package dev.jasagent.web.upstream;

/**
 * 控制台上游的本机约定。这个进程只提供页面，登录接口留在网关。
 */
public final class WebUpstream {
    /**
     * 未另行指定时绑定的地址。
     */
    public static final String DEFAULT_HOST = "127.0.0.1";
    /**
     * 未另行指定时绑定的端口。不能占用网关的 18721，也不能占用 agent 的 18731 和 18732。
     */
    public static final int DEFAULT_PORT = 18722;
    /**
     * 构建产物在 classpath 上的目录。Vite 把页面写到这里，网关再按路径反代过来。
     */
    public static final String CLASSPATH_LOCATION = "classpath:/admin/";
    /**
     * 浏览器访问控制台时的路径前缀，和 Vite 的 base 一致。
     */
    public static final String PAGE_PREFIX = "/admin";

    /**
     * 禁止实例化。约定只通过常量使用。
     */
    private WebUpstream() {
    }
}
