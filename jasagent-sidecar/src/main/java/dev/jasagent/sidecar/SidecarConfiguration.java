package dev.jasagent.sidecar;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 组装出站 sidecar 的放行名单、通行证客户端和上游客户端。
 * 这些对象在启动时只保存配置，不连接 agentpass，也不访问大模型主机。
 */
@Configuration
public class SidecarConfiguration {
    /**
     * 用配置里的额外主机加上内置的两家大模型主机，组成免证放行名单。
     *
     * @param properties 含 {@code JASAGENT_LLM_ALLOWLIST} 原文的配置
     * @return 只认完整主机名的放行名单
     */
    @Bean
    public LlmAllowlist llmAllowlist(SidecarProperties properties) {
        return LlmAllowlist.parse(properties.getLlmAllowlist());
    }

    /**
     * 建立访问已有 agentpass 的客户端。校验走 {@code POST /v1/tickets/verify}，核销走 {@code POST /v1/tickets/redeem}。
     *
     * @param properties 根地址和超时
     * @return 不在日志里记录证明文的客户端
     */
    @Bean
    public TicketOffice ticketOffice(SidecarProperties properties) {
        return new HttpTicketOffice(properties.getAgentpassBaseUrl(), properties.getAgentpassTimeoutMillis());
    }

    /**
     * 建立真正拨号到上游的客户端。不跟随重定向，避免放行一次之后被带到名单外的主机。
     *
     * @param properties 上游超时
     * @return JDK HTTP 客户端
     */
    @Bean
    public UpstreamClient upstreamClient(SidecarProperties properties) {
        return new JdkUpstreamClient(properties.getUpstreamTimeoutMillis());
    }

    /**
     * 把放行判断、通行证核销和上游转发串成一次出站处理。
     *
     * @param allowlist 免证主机
     * @param office 通行证校验和核销
     * @param upstream 上游转发
     * @return 控制器调用的出站服务
     */
    @Bean
    public EgressService egressService(LlmAllowlist allowlist, TicketOffice office, UpstreamClient upstream) {
        return new EgressService(allowlist, office, upstream);
    }

    /**
     * 验收需要把 Tomcat 的操作系统平台工作线程压低时，安装固定大小的平台线程池。
     * 配置小于 1 时定制器不替换线程池，生产默认因此保持 Tomcat 原样。
     *
     * @param properties 含平台线程上限的配置
     * @return 只在上限至少为 1 时改执行器的定制器
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatPlatformWorkers(SidecarProperties properties) {
        return new TomcatPlatformWorkerCustomizer(properties);
    }
}
