package dev.jasagent.it;

import dev.jasagent.gateway.SessionMediaGatewayTest;
import dev.jasagent.gateway.agent.AgentDiscovery;
import dev.jasagent.gateway.agent.AgentInstance;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * 给附件测试一台桩 agent。放在网关扫描包外面，避免别的测试也装上它。
 */
@TestConfiguration
public class SessionMediaAgentsConfiguration {
    /**
     * 只有测试里那台桩。正式的 Nacos 发现仍然存在，但注入时优先用这份。
     *
     * @return 测试发现
     */
    @Bean
    @Primary
    AgentDiscovery sessionMediaStub() {
        int port = SessionMediaGatewayTest.agentPort();
        return () -> List.of(new AgentInstance("agent-" + port, "127.0.0.1", port));
    }
}
