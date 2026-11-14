package dev.jasagent.it;

import dev.jasagent.gateway.GatewayForwardTest;
import dev.jasagent.gateway.agent.AgentDiscovery;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 给转发测试提供发现结果。放在网关扫描包外面，避免别的测试也装上它。
 */
@TestConfiguration
public class DiscoveredAgentsConfiguration {
    /**
     * 使用测试里那份可变发现。正式的 Nacos 发现仍然存在，但注入时优先用这份。
     *
     * @return 测试发现
     */
    @Bean
    @Primary
    AgentDiscovery discoveredAgents() {
        return GatewayForwardTest.discovered;
    }
}
