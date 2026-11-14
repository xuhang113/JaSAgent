package dev.jasagent.app.nacos;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 用 nacos-client 2.4.3 注册和注销。这个版本不依赖 Spring Cloud Alibaba，能和 Spring Boot 3.4.5 放在一起。
 * 异常文本固定，不转发客户端自带的地址或口令。
 */
public final class NacosClientNaming implements NacosNaming {
    static {
        if (System.getProperty("nacos.logging.default.config.enabled") == null) {
            System.setProperty("nacos.logging.default.config.enabled", "false");
        }
    }

    private final NamingService naming;

    /**
     * 使用已经建好的命名服务。测试可以传入不连网的实现。
     *
     * @param naming Nacos 命名服务
     */
    public NacosClientNaming(NamingService naming) {
        this.naming = naming;
    }

    /**
     * 按仓库外的地址和口令建立客户端。口令只放进客户端属性，不出现在异常里。
     *
     * @param settings 连接参数，必须已经配置地址
     * @return 连到该 Nacos 的命名操作
     */
    public static NacosClientNaming connect(NacosSettings settings) {
        try {
            return new NacosClientNaming(NamingFactory.createNamingService(properties(settings)));
        } catch (NacosException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "Nacos 不可用");
        }
    }

    /**
     * 组装客户端属性。没有账号时不放口令。调用方不得打印返回值。
     *
     * @param settings 连接参数
     * @return 交给 {@link NamingFactory} 的属性
     */
    static Properties properties(NacosSettings settings) {
        if (settings == null || !settings.configured()) {
            throw new AgentException(ErrorCode.INTERNAL, "Nacos 不可用");
        }
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, settings.serverAddr());
        if (!settings.namespace().isEmpty()) {
            properties.setProperty(PropertyKeyConst.NAMESPACE, settings.namespace());
        }
        if (!settings.username().isEmpty()) {
            properties.setProperty(PropertyKeyConst.USERNAME, settings.username());
            properties.setProperty(PropertyKeyConst.PASSWORD, settings.password());
        }
        return properties;
    }

    /**
     * 注册临时健康实例，并在元数据里写下稳定编号。回环地址直接拒绝，不会发到 Nacos。
     *
     * @param service 服务名
     * @param group 分组
     * @param instanceId 稳定编号
     * @param ip 注册主机
     * @param port 注册端口
     */
    @Override
    public void register(String service, String group, String instanceId, String ip, int port) {
        if (!ReachableAddress.usable(ip)) {
            throw new AgentException(ErrorCode.INTERNAL, ReachableAddress.REJECTED);
        }
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setInstanceId(instanceId);
        instance.setEphemeral(true);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setWeight(1.0D);
        Map<String, String> metadata = new HashMap<>();
        metadata.put(NacosSettings.META_INSTANCE_ID, instanceId);
        instance.setMetadata(metadata);
        try {
            naming.registerInstance(service, group, instance);
        } catch (NacosException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "向 Nacos 注册失败");
        }
    }

    /**
     * 按主机和端口注销。失败时不把客户端的原始说明带出去。
     *
     * @param service 服务名
     * @param group 分组
     * @param ip 注册主机
     * @param port 注册端口
     */
    @Override
    public void deregister(String service, String group, String ip, int port) {
        try {
            naming.deregisterInstance(service, group, ip, port);
        } catch (NacosException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "从 Nacos 注销失败");
        }
    }

    /**
     * 关闭命名客户端，停止临时实例心跳。
     */
    @Override
    public void shutdown() {
        try {
            naming.shutDown();
        } catch (NacosException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "Nacos 客户端没有关闭");
        }
    }
}
