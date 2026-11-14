package dev.jasagent.app.nacos;

/**
 * 没有配置 Nacos 地址时的空实现。不连接注册中心，也不注册回环地址。
 */
public final class NoopNacosNaming implements NacosNaming {
    /**
     * 创建一个什么都不做的命名操作。
     */
    public NoopNacosNaming() {
    }

    /**
     * 不注册。没有地址时不能假装已经登记。
     *
     * @param service 服务名
     * @param group 分组
     * @param instanceId 稳定编号
     * @param ip 主机
     * @param port 端口
     */
    @Override
    public void register(String service, String group, String instanceId, String ip, int port) {
    }

    /**
     * 不注销。没有登记过的实例不需要再通知注册中心。
     *
     * @param service 服务名
     * @param group 分组
     * @param ip 主机
     * @param port 端口
     */
    @Override
    public void deregister(String service, String group, String ip, int port) {
    }

    /**
     * 不关闭外部客户端。这里没有连接。
     */
    @Override
    public void shutdown() {
    }
}
