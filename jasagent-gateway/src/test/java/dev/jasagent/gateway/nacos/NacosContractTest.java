package dev.jasagent.gateway.nacos;

import dev.jasagent.gateway.GatewayRuntime;
import dev.jasagent.gateway.agent.AgentInstance;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关源码不再把静态目录当发现来源，并且仍按单副本把会话和存活留在进程内。
 */
class NacosContractTest {
    /**
     * 空白解析不再回到两台回环实例。主源码不调用 primary、secondary、defaults 或 parse。
     */
    @Test
    void staticCatalogIsNotADiscoverySource() throws Exception {
        assertTrue(AgentInstance.parse("").isEmpty());
        assertTrue(AgentInstance.parse(null).isEmpty());
        assertTrue(AgentInstance.parse("   ").isEmpty());
        String agent = Files.readString(Path.of("src/main/java/dev/jasagent/gateway/agent/AgentInstance.java"));
        String parse = agent.substring(agent.indexOf("List<AgentInstance> parse"));
        assertFalse(parse.contains("defaults()"));
        assertFalse(parse.contains("primary()"));
        assertFalse(parse.contains("secondary()"));
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java"))) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("AgentInstance.java")) {
                    continue;
                }
                String text = Files.readString(file);
                assertFalse(text.contains("AgentInstance.defaults()"), file.toString());
                assertFalse(text.contains("AgentInstance.primary()"), file.toString());
                assertFalse(text.contains("AgentInstance.secondary()"), file.toString());
                assertFalse(text.contains("AgentInstance.parse("), file.toString());
                assertFalse(text.contains("jasagent.gateway.agents"), file.toString());
            }
        }
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        assertFalse(props.contains("jasagent.gateway.agents"));
        assertFalse(props.contains("password="));
        assertTrue(props.contains("jasagent.nacos.server-addr=${JASAGENT_NACOS_SERVER_ADDR:}"));
        assertTrue(props.contains(GatewayRuntime.SINGLE_REPLICA));
        assertEquals(1, GatewayRuntime.REPLICAS);
    }

    /**
     * 登录会话和存活探测仍在网关进程里，本轮副本数是 1。
     */
    @Test
    void gatewayStaysSingleReplica() throws Exception {
        String configuration = Files.readString(Path.of("src/main/java/dev/jasagent/gateway/config/GatewayConfiguration.java"));
        assertTrue(configuration.contains("InMemoryAgentLiveness"));
        assertTrue(configuration.contains("LocalAuthService"));
        assertTrue(configuration.contains(GatewayRuntime.SINGLE_REPLICA));
        String session = Files.readString(Path.of("src/main/java/dev/jasagent/gateway/auth/GatewaySessionFilter.java"));
        assertTrue(session.contains(GatewayRuntime.SINGLE_REPLICA));
        String liveness = Files.readString(Path.of("src/main/java/dev/jasagent/gateway/agent/InMemoryAgentLiveness.java"));
        assertTrue(liveness.contains(GatewayRuntime.SINGLE_REPLICA));
        String start = Files.readString(Path.of("../scripts/start.sh"));
        assertTrue(start.contains("jasagent_prepare_nacos"));
        assertTrue(start.contains("--server.address=0.0.0.0"));
        assertFalse(start.contains("jasagent.gateway.agents"));
        Path script = Path.of("../scripts/test-017-nacos-agent-discovery.sh");
        assertTrue(Files.isExecutable(script));
    }
}
