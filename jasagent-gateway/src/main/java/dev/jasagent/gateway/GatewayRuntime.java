package dev.jasagent.gateway;

/**
 * 网关进程的运行约定。浏览器缺省仍访问 127.0.0.1:18721。
 * 登录会话在 MySQL。存活探测按网关副本写入同一库并跨副本读取。本机缺省副本数仍是 1。
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
     * 本机配置接受的缺省副本数。集群扩到至少 2 个就绪副本时改部署清单，不在这里改。
     * 存活状态已经按副本共享，这个数字不再表示存活只能留在一个进程里。
     */
    public static final int REPLICAS = 1;
    /**
     * 写在配置和注释里的口径。登录会话在库里，存活探测按副本共享；本机缺省副本数仍是 1。
     */
    public static final String SINGLE_REPLICA = "登录会话在 MySQL。存活探测按网关副本写入同一库并跨副本读取。本机缺省副本数仍是 1。";

    /**
     * 禁止实例化。约定只通过常量使用。
     */
    private GatewayRuntime() {
    }
}
