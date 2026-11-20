package dev.jasagent.agentpass.server;

import dev.jasagent.auth.MysqlAccountAuth;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通行证服务自己的运行参数。签名密钥和数据库口令只从环境或外部配置进入内存，不写进仓库里的属性文件。
 * {@link #toString()} 不包含密钥或口令。
 */
@ConfigurationProperties(prefix = "jasagent.agentpass")
public class AgentPassServerProperties {
    /**
     * 一次性通行证从签发时刻起的有效秒数。小于 1 时服务拒绝启动，避免发出已经作废的证。
     */
    private long ttlSeconds = 120;

    /**
     * HMAC 签名密钥的原文。空白时服务拒绝启动，不再另生成一把。
     * 真实密钥不要写进仓库、日志或测试夹具；生产用环境变量 {@code JASAGENT_AGENTPASS_SIGNING_KEY}。
     */
    private String signingKey = "";
    /**
     * 证状态所在库的 JDBC 地址，与 020 同一套库。空白时用本机 jasagent 地址。地址里不能带 password=。
     */
    private String jdbcUrl = "";
    /**
     * 数据库账号。空白时再看环境变量，最后用 root。
     */
    private String jdbcUser = "";
    /**
     * 数据库口令。空白时再看环境变量和本机口令文件。调用方不得把它写进日志。
     */
    private String jdbcPassword = "";

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

    /**
     * 返回 JDBC 地址。空白表示调用方应改用 {@link MysqlAccountAuth#DEFAULT_URL} 或环境变量。
     *
     * @return 配置里的地址；没有配置时是空字符串
     */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * 写入 JDBC 地址。Spring 绑定配置时调用。
     *
     * @param jdbcUrl 不含口令的地址
     */
    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * 返回数据库账号。
     *
     * @return 配置里的账号；没有配置时是空字符串
     */
    public String getJdbcUser() {
        return jdbcUser;
    }

    /**
     * 写入数据库账号。Spring 绑定配置时调用。
     *
     * @param jdbcUser 数据库账号
     */
    public void setJdbcUser(String jdbcUser) {
        this.jdbcUser = jdbcUser;
    }

    /**
     * 返回数据库口令。调用方不得把它写进日志、异常消息或响应。
     *
     * @return 配置里的口令；没有配置时是空字符串
     */
    public String getJdbcPassword() {
        return jdbcPassword;
    }

    /**
     * 写入数据库口令。Spring 绑定配置时调用。
     *
     * @param jdbcPassword 口令原文
     */
    public void setJdbcPassword(String jdbcPassword) {
        this.jdbcPassword = jdbcPassword;
    }

    /**
     * 返回不含密钥和口令的说明，避免被日志框架打出来。
     *
     * @return 只含有效秒数的文本
     */
    @Override
    public String toString() {
        return "AgentPassServerProperties[ttlSeconds=" + ttlSeconds + "]";
    }
}
