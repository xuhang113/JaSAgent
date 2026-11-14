package dev.jasagent.it;

import dev.jasagent.gateway.token.InternalToken;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 让控制台测试看到未配置的内部令牌。这个类不在网关的组件扫描里。
 */
@TestConfiguration
public class UnsetTokenConfiguration {
    /**
     * 空令牌表示环境变量没有配置。可转发请求应停在网关。
     *
     * @return 未配置的内部令牌
     */
    @Bean
    @Primary
    public InternalToken unsetInternalToken() {
        return InternalToken.of("");
    }
}
