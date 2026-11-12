package dev.jasagent.agentpass.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通行证服务自己的运行参数。签名密钥只从环境或外部配置进入内存，不写进仓库里的属性文件。
 */
@ConfigurationProperties(prefix = "jasagent.agentpass")
public class AgentPassServerProperties {
    /**
     * 一次性通行证从签发时刻起的有效秒数。小于 1 时服务拒绝启动，避免发出已经作废的证。
     */
    private long ttlSeconds = 120;

    /**
     * HMAC 签名密钥的原文。空字符串表示启动时另生成一把只活在本进程内存里的密钥。
     * 真实密钥不要写进仓库、日志或测试夹具；生产用环境变量 {@code JASAGENT_AGENTPASS_SIGNING_KEY}。
     */
    private String signingKey = "";

    /**
     * 返回配置好的有效秒数。服务在创建时还会拒绝小于 1 的值。
     *
     * @return 通行证有效秒数，缺省是 120
     */
    public long getTtlSeconds() {
        return ttlSeconds;
    }

    /**
     * 写入通行证有效秒数。Spring 绑定配置时调用。
     *
     * @param ttlSeconds 从签发时刻起算的秒数
     */
    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 返回签名密钥原文。调用方不得把它写进日志、异常消息或审计流水。
     *
     * @return 配置里的密钥；没有配置时是空字符串
     */
    public String getSigningKey() {
        return signingKey;
    }

    /**
     * 写入签名密钥原文。Spring 绑定配置或环境变量时调用。
     *
     * @param signingKey 密钥原文；空白视为没有配置
     */
    public void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
    }
}
