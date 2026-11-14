package dev.jasagent.app.nacos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 agent 的 Nacos 注册。地址和口令来自环境或仓库外文件。
 * {@code jasagent.nacos.client=recording} 只给测试替换命名操作，正式启动不设置它。
 */
@Configuration
public class NacosRegistrationConfiguration {
    /**
     * 读取 Nacos 配置。口令文件缺失且账号非空时，这里就会拒绝。
     *
     * @param serverAddr 服务端地址
     * @param username 账号
     * @param passwordFile 口令文件
     * @param namespace 命名空间
     * @param group 分组
     * @param service 服务名
     * @param advertiseHost 注册主机
     * @param bindAddress 监听地址
     * @return 连接参数
     */
    @Bean
    public NacosSettings nacosSettings(
            @Value("${jasagent.nacos.server-addr:}") String serverAddr,
            @Value("${jasagent.nacos.username:}") String username,
            @Value("${jasagent.nacos.password-file:}") String passwordFile,
            @Value("${jasagent.nacos.namespace:}") String namespace,
            @Value("${jasagent.nacos.group:DEFAULT_GROUP}") String group,
            @Value("${jasagent.nacos.service-name:jasagent-agent}") String service,
            @Value("${jasagent.nacos.advertise-host:}") String advertiseHost,
            @Value("${server.address:}") String bindAddress) {
        return NacosSettings.load(serverAddr, username, passwordFile, namespace, group, service, advertiseHost, bindAddress);
    }

    /**
     * 建立命名操作。未配置地址时用空实现；测试把 {@code jasagent.nacos.client} 设为 recording 时不创建真实客户端。
     *
     * @param settings 连接参数
     * @return 命名操作
     */
    @Bean
    @ConditionalOnProperty(name = "jasagent.nacos.client", havingValue = "nacos", matchIfMissing = true)
    public NacosNaming nacosNaming(NacosSettings settings) {
        if (!settings.configured()) {
            return new NoopNacosNaming();
        }
        return NacosClientNaming.connect(settings);
    }

    /**
     * 就绪后注册、关闭时注销的生命周期。
     *
     * @param naming 命名操作
     * @param settings 连接参数
     * @return 注册器
     */
    @Bean
    public NacosAgentRegistration nacosAgentRegistration(NacosNaming naming, NacosSettings settings) {
        return new NacosAgentRegistration(naming, settings);
    }
}
