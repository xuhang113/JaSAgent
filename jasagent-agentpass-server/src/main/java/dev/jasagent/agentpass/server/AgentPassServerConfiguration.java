package dev.jasagent.agentpass.server;

import dev.jasagent.agentpass.durable.AgentPassLedger;
import dev.jasagent.agentpass.durable.DurablePassGate;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.auth.LocalDirectories;
import dev.jasagent.auth.MysqlAccountAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * 把 020 的 MySQL 账本、租户策略门和一次性证服务装进 Spring。
 * 缺少签名密钥或数据库口令时拒绝启动，异常和日志都不写密钥或口令。
 */
@Configuration
public class AgentPassServerConfiguration {
    private static final Logger log = LoggerFactory.getLogger(AgentPassServerConfiguration.class);

    /**
     * 用家目录根建立和主程序相同的用户目录布局。发证不再读取这里的会话或策略文件。
     *
     * @param home {@code jasagent.home}，缺省是进程的用户家目录
     * @return 登录名到 {@code 家目录/登录名/jasagent} 的布局
     */
    @Bean
    public LocalDirectories localDirectories(@Value("${jasagent.home:${user.home}}") String home) {
        return new LocalDirectories(Path.of(home));
    }

    /**
     * 打开与 020 相同的库，并建好证状态、流水、策略和待批表。口令缺失时拒绝启动。
     *
     * @param properties 含可选 JDBC 地址、账号和口令的配置
     * @return 已建表的通行证账本
     */
    @Bean
    public AgentPassLedger agentPassLedger(AgentPassServerProperties properties) {
        String url = first(properties.getJdbcUrl(), System.getenv("JASAGENT_JDBC_URL"), MysqlAccountAuth.DEFAULT_URL);
        if (url.toLowerCase().contains("password=")) {
            throw new IllegalStateException("JDBC 地址不能携带口令");
        }
        String user = first(properties.getJdbcUser(), System.getenv("JASAGENT_JDBC_USER"), "root");
        String password = first(properties.getJdbcPassword(), System.getenv("JASAGENT_JDBC_PASSWORD"), readPasswordFile());
        if (password.isBlank()) {
            throw new IllegalStateException("没有找到本机 MySQL 口令");
        }
        return AgentPassLedger.open(url, user, password);
    }

    /**
     * 创建按租户账本裁决的通行证门。未命中会留下待批，不再读 policies.yaml。
     *
     * @param ledger 通行证账本
     * @return 持久化的通行证门
     */
    @Bean
    public PassGate passGate(AgentPassLedger ledger) {
        return new DurablePassGate(ledger);
    }

    /**
     * 提供签发和校验用的时钟。测试可以另行提供同类型的豆来替换。
     *
     * @return UTC 系统时钟
     */
    @Bean
    public Clock agentPassClock() {
        return Clock.systemUTC();
    }

    /**
     * 按配置创建签名器。密钥为空白时启动失败。日志只说明密钥来自配置，不写密钥内容。
     *
     * @param properties 含签名密钥的配置
     * @return 证明文签名器
     */
    @Bean
    public PassSigner passSigner(AgentPassServerProperties properties) {
        PassSigner signer = PassSigner.create(properties.getSigningKey());
        log.info("agentpass signing key loaded from configuration and is not logged");
        return signer;
    }

    /**
     * 创建落到账本的证登记。
     *
     * @param ledger 通行证账本
     * @return 证状态存储
     */
    @Bean
    public PassStore passStore(AgentPassLedger ledger) {
        return new PassStore(ledger);
    }

    /**
     * 创建落到账本的流水，时间取通行证时钟。
     *
     * @param clock 与过期判断相同的时钟
     * @param ledger 通行证账本
     * @return 流水
     */
    @Bean
    public AuditLog auditLog(Clock clock, AgentPassLedger ledger) {
        return new AuditLog(clock, ledger);
    }

    /**
     * 用账本做发证前的人证检查。会话、租户和策略都来自 MySQL。
     *
     * @param ledger 通行证账本
     * @return 人证检查
     */
    @Bean
    public HumanAccess humanAccess(AgentPassLedger ledger) {
        return new HumanAccess(ledger);
    }

    /**
     * 创建一次性证服务。有效秒数小于 1 时启动失败，异常消息不含密钥。
     *
     * @param access 人证检查
     * @param signer 证明文签名器
     * @param store 证登记
     * @param audit 流水
     * @param clock 时钟
     * @param properties 有效秒数配置
     * @return 领证、校验和核销服务
     */
    @Bean
    public OneTimePassService oneTimePassService(HumanAccess access, PassSigner signer, PassStore store, AuditLog audit,
                                                  Clock clock, AgentPassServerProperties properties) {
        if (properties.getTtlSeconds() < 1) {
            throw new IllegalStateException("jasagent.agentpass.ttl-seconds");
        }
        return new OneTimePassService(access, signer, store, audit, clock, Duration.ofSeconds(properties.getTtlSeconds()));
    }

    /**
     * 按顺序取第一个非空白文本。
     *
     * @param values 候选文本
     * @return 第一个非空白值；全空白时为空串
     */
    private static String first(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    /**
     * 读取本机口令文件的第一行。文件不存在或读失败时返回空串，不把路径内容写进异常。
     *
     * @return 去掉首尾空白后的口令；没有时是空串
     */
    private static String readPasswordFile() {
        Path file = Path.of(System.getProperty("user.home", ""), ".config", "urag-mysql-root.password");
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            String line = Files.readString(file, StandardCharsets.UTF_8);
            int newline = line.indexOf('\n');
            if (newline >= 0) {
                line = line.substring(0, newline);
            }
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            return line.trim();
        } catch (Exception ex) {
            return "";
        }
    }
}
