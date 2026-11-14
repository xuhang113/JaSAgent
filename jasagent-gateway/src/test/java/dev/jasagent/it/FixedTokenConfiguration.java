package dev.jasagent.it;

import dev.jasagent.gateway.token.InternalToken;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 给转发测试一个固定的内部令牌。这个类不在网关的组件扫描里，只有显式导入的测试会用到。
 */
@TestConfiguration
public class FixedTokenConfiguration {
    /**
     * 测试用假令牌。不是仓库里的密钥，也不会写进响应。
     */
    public static final String TOKEN = "gateway-it-token";

    /**
     * 覆盖从进程环境读取的令牌，让这次测试能观察四个转发头。
     *
     * @return 已配置的内部令牌
     */
    @Bean
    @Primary
    public InternalToken fixedInternalToken() {
        return InternalToken.of(TOKEN);
    }
}
