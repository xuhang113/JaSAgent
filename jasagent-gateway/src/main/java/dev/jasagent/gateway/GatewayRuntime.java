package dev.jasagent.gateway;

/**
 * 网关进程的运行约定。登录和会话留在本进程，存活探测也留在本进程的内存里。
 * 登录会话和存活探测都在网关进程内，本轮网关按单副本验收。
 */
public final class GatewayRuntime {
    /**
     * 未设置 JASAGENT_HOST 时绑定的地址。
     */
    public static final String DEFAULT_HOST = "127.0.0.1";
    /**
     * 未设置 JASAGENT_PORT 时绑定的端口。浏览器和命令行都访问这里。
     */
    public static final int DEFAULT_PORT = 18721;
    /**
     * 本机控制台上游的默认端口。jasagent-web 听这个端口，网关不占用它。
     */
    public static final int WEB_PORT = 18722;
    /**
     * 本轮接受的网关副本数。会话和存活都不在进程之间共享。
     */
    public static final int REPLICAS = 1;
    /**
     * 写在配置和注释里的单副本口径，避免只把存活探测说成单副本。
     */
    public static final String SINGLE_REPLICA = "登录会话和存活探测都在网关进程内，本轮网关按单副本验收。";

    /**
     * 禁止实例化。约定只通过常量使用。
     */
    private GatewayRuntime() {
    }
}
