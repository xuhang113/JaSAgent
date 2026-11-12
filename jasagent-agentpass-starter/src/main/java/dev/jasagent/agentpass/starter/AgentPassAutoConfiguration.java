package dev.jasagent.agentpass.starter;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 引入 starter 后自动登记通行证过滤器。默认开启；只有显式把 {@code jasagent.agentpass.filter.enabled} 设为 false 才不登记。
 * 这个开关和进程内 {@code jasagent.agentpass.enabled} 不是同一个配置。
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentPassFilterProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "jasagent.agentpass.filter", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentPassAutoConfiguration {
    /**
     * 创建访问本机通行证服务的客户端。测试可以自行提供同类型的豆来替换。
     *
     * @param properties 根地址和超时
     * @return HTTP 客户端
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentPassClient agentPassClient(AgentPassFilterProperties properties) {
        return new HttpAgentPassClient(properties);
    }

    /**
     * 创建过滤器实例。容器注册由旁边的登记豆完成，这里不再把它标成组件，避免登记两次。
     *
     * @param client 校验和核销客户端
     * @param properties 流量规则
     * @return 通行证过滤器
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentPassFilter agentPassFilter(AgentPassClient client, AgentPassFilterProperties properties) {
        return new AgentPassFilter(client, properties);
    }

    /**
     * 把过滤器登记到所有路径的请求分发上，顺序放在最前，让业务控制器在核销成功之前碰不到请求。
     *
     * @param filter 已经创建的过滤器
     * @return 只处理普通请求分发的登记
     */
    @Bean
    public FilterRegistrationBean<AgentPassFilter> agentPassFilterRegistration(AgentPassFilter filter) {
        FilterRegistrationBean<AgentPassFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setName("agentPassFilter");
        return registration;
    }
}
