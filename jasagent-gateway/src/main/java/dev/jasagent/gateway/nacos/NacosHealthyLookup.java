package dev.jasagent.gateway.nacos;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.List;
import java.util.Properties;

/**
 * 用 nacos-client 2.4.3 查询健康实例。只向命名服务要 healthy=true 的结果。
 * 客户端异常被换成固定说明，不带出地址或口令。
 */
public final class NacosHealthyLookup {
    static {
        if (System.getProperty("nacos.logging.default.config.enabled") == null) {
            System.setProperty("nacos.logging.default.config.enabled", "false");
        }
    }

    private final NamingService naming;
    private final String service;
    private final String group;

    /**
     * 绑定已经建好的命名服务。测试可以传入不连网的实现。
     *
     * @param naming 命名服务
     * @param service 服务名
     * @param group 分组
     */
    public NacosHealthyLookup(NamingService naming, String service, String group) {
        this.naming = naming;
        this.service = service;
        this.group = group;
    }

    /**
     * 按仓库外参数建立查询。口令只放进客户端属性。
     *
     * @param settings 连接参数
     * @return 查询
     */
    public static NacosHealthyLookup connect(NacosSettings settings) {
        try {
            return new NacosHealthyLookup(NamingFactory.createNamingService(properties(settings)), settings.service(), settings.group());
        } catch (NacosException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "Nacos 不可用");
        }
    }

    /**
     * 组装客户端属性。没有账号时不放口令。调用方不得打印返回值。
     *
     * @param settings 连接参数
     * @return 客户端属性
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
     * 查询当前健康实例。失败时抛出固定说明，不回退到静态目录。
     *
     * @return Nacos 返回的健康实例，可能为空
     */
    public List<Instance> healthyNow() {
        try {
            List<Instance> found = naming.selectInstances(service, group, true);
            if (found == null) {
                return List.of();
            }
            return List.copyOf(found);
        } catch (NacosException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "Nacos 不可用");
        }
    }
}
