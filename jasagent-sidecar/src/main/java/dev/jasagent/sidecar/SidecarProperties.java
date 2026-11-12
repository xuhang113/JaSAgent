package dev.jasagent.sidecar;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 出站 sidecar 的运行参数。通行证服务地址、超时和额外放行主机都从这里读。
 * 配置值里不放模型密钥或通行证签名密钥；那些只留在调用方自己的请求里，并且不会被本进程写进日志。
 */
@ConfigurationProperties(prefix = "jasagent.sidecar")
public class SidecarProperties {
    /**
     * 已有 agentpass 的根地址，不含 {@code /v1/tickets}。校验和核销都拼在这个根地址后面。
     */
    private String agentpassBaseUrl = "http://127.0.0.1:18741";
    /**
     * 访问通行证服务的超时毫秒数。超时视为不可达，对应的外部请求不转发。
     */
    private long agentpassTimeoutMillis = 3000;
    /**
     * 等待上游响应的超时毫秒数。超时不转发成功，也不把异常原文写进日志。
     */
    private long upstreamTimeoutMillis = 60000;
    /**
     * {@code JASAGENT_LLM_ALLOWLIST} 的内容。逗号或空白分隔的额外主机，和内置大模型主机一起免证放行。
     */
    private String llmAllowlist = "";

    /**
     * 返回通行证服务根地址。
     *
     * @return 不含校验路径的根地址；缺省指向本机 18741 上已有的 agentpass
     */
    public String getAgentpassBaseUrl() {
        return agentpassBaseUrl;
    }

    /**
     * 写入通行证服务根地址。空白时后续校验按不可达处理，不会猜一个地址。
     *
     * @param agentpassBaseUrl 不含 {@code /v1/tickets/verify} 的根地址
     */
    public void setAgentpassBaseUrl(String agentpassBaseUrl) {
        this.agentpassBaseUrl = agentpassBaseUrl;
    }

    /**
     * 返回访问通行证服务的超时。
     *
     * @return 超时毫秒数，缺省 3000
     */
    public long getAgentpassTimeoutMillis() {
        return agentpassTimeoutMillis;
    }

    /**
     * 写入访问通行证服务的超时。小于 1 时客户端会按 1 毫秒处理，避免零超时变成无限等待。
     *
     * @param agentpassTimeoutMillis 超时毫秒数
     */
    public void setAgentpassTimeoutMillis(long agentpassTimeoutMillis) {
        this.agentpassTimeoutMillis = agentpassTimeoutMillis;
    }

    /**
     * 返回等待上游的超时。
     *
     * @return 超时毫秒数，缺省 60000
     */
    public long getUpstreamTimeoutMillis() {
        return upstreamTimeoutMillis;
    }

    /**
     * 写入等待上游的超时。
     *
     * @param upstreamTimeoutMillis 超时毫秒数
     */
    public void setUpstreamTimeoutMillis(long upstreamTimeoutMillis) {
        this.upstreamTimeoutMillis = upstreamTimeoutMillis;
    }

    /**
     * 返回额外放行主机的原文。
     *
     * @return 环境变量 {@code JASAGENT_LLM_ALLOWLIST} 的值；未设置时为空串
     */
    public String getLlmAllowlist() {
        return llmAllowlist;
    }

    /**
     * 写入额外放行主机。解析发生在 {@link LlmAllowlist#parse(String)}，这里只保存原文。
     *
     * @param llmAllowlist 逗号或空白分隔的主机，可以是空串
     */
    public void setLlmAllowlist(String llmAllowlist) {
        this.llmAllowlist = llmAllowlist;
    }
}
