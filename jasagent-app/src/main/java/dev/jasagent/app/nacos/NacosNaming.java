package dev.jasagent.app.nacos;

/**
 * agent 向 Nacos 注册和注销的操作。生产实现使用 nacos-client 2.4.3，测试可以换一个不连网的实现。
 * 实现不得把地址或口令写进异常。
 */
public interface NacosNaming {
    /**
     * 注册一台健康的临时实例。地址必须已经是非回环地址。
     *
     * @param service 服务名
     * @param group 分组
     * @param instanceId 稳定编号
     * @param ip 注册主机
     * @param port 注册端口
     */
    void register(String service, String group, String instanceId, String ip, int port);

    /**
     * 按主机和端口注销。实例已经不在时也要结束，不能留下继续接流量的登记。
     *
     * @param service 服务名
     * @param group 分组
     * @param ip 注册主机
     * @param port 注册端口
     */
    void deregister(String service, String group, String ip, int port);

    /**
     * 关掉客户端，停止临时实例的心跳。
     */
    void shutdown();
}
