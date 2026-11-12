package dev.jasagent.agentpass.server;

import dev.jasagent.agentpass.PolicyPassGate;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.auth.LocalDirectories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * 把本机登录、进程内策略门和一次性证服务装进 Spring。策略门保持 ALLOW、DENY、PENDING 的原有语义。
 */
@Configuration
public class AgentPassServerConfiguration {
    private static final Logger log = LoggerFactory.getLogger(AgentPassServerConfiguration.class);

    /**
     * 用家目录根建立和主程序相同的用户目录布局。
     *
     * @param home {@code jasagent.home}，缺省是进程的用户家目录
     * @return 登录名到 {@code 家目录/登录名/jasagent} 的布局
     */
    @Bean
    public LocalDirectories localDirectories(@Value("${jasagent.home:${user.home}}") String home) {
        return new LocalDirectories(Path.of(home));
    }

    /**
     * 创建进程内策略门。未命中仍是 PENDING，命中策略仍是 ALLOW 或 DENY，本服务不改这些决定的含义。
     *
     * @param directories 用来按登录名找到用户自己的策略文件
     * @return 只读用户策略的通行证门
     */
    @Bean
    public PassGate passGate(LocalDirectories directories) {
        return new PolicyPassGate(directories::userDirectory);
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
     * 按配置创建签名器。日志只说明密钥来自配置还是本进程生成，不写密钥内容。
     *
     * @param properties 含可选签名密钥的配置
     * @return 证明文签名器
     */
    @Bean
    public PassSigner passSigner(AgentPassServerProperties properties) {
        PassSigner signer = PassSigner.create(properties.getSigningKey());
        if (signer.ephemeral()) {
            log.info("agentpass signing key is process-local and is not logged");
        } else {
            log.info("agentpass signing key loaded from configuration and is not logged");
        }
        return signer;
    }

    /**
     * 创建本进程的证登记。
     *
     * @return 空的通行证存储
     */
    @Bean
    public PassStore passStore() {
        return new PassStore();
    }

    /**
     * 创建流水，时间取通行证时钟。
     *
     * @param clock 与过期判断相同的时钟
     * @return 空的流水
     */
    @Bean
    public AuditLog auditLog(Clock clock) {
        return new AuditLog(clock);
    }

    /**
     * 把用户目录和策略门合成发证前的人证检查。会话按编号读文件，不接收登录令牌。
     *
     * @param directories 用户目录布局
     * @param passGate 进程内策略门
     * @return 人证检查
     */
    @Bean
    public HumanAccess humanAccess(LocalDirectories directories, PassGate passGate) {
        return new HumanAccess(directories, passGate);
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
}
