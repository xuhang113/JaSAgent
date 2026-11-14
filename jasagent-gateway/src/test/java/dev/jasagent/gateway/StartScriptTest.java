package dev.jasagent.gateway;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日常入口和沙箱入口都把 18721 当成网关，并拉起控制台上游和两台 agent。
 */
class StartScriptTest {
    /**
     * start.sh 在默认端口拉起网关，就绪不依赖应用启动完成那一行日志。
     */
    @Test
    void startScriptLaunchesGatewayWebAndAgents() throws Exception {
        String script = Files.readString(Path.of("../scripts/start.sh"));
        assertTrue(script.contains("port=\"${JASAGENT_PORT:-18721}\""));
        assertTrue(script.contains("web_port=\"${JASAGENT_WEB_PORT:-18722}\""));
        assertTrue(script.contains("agent_primary=18731"));
        assertTrue(script.contains("agent_secondary=18732"));
        assertTrue(script.contains("jasagent-gateway"));
        assertTrue(script.contains("JASAGENT_WEB_JAR"));
        assertTrue(script.contains("JASAGENT_APP_JAR"));
        assertTrue(script.contains("jasagent_wait_http_up"));
        assertTrue(script.contains("/health"));
        assertFalse(script.contains("Started JasagentApplication"));
        String common = Files.readString(Path.of("../scripts/common.sh"));
        assertTrue(common.contains("找不到网关 jar。"));
        assertTrue(common.contains("找不到控制台上游。"));
        assertFalse(common.contains("JASAGENT_INTERNAL_TOKEN="));
    }

    /**
     * 沙箱入口改走同一条 start.sh，不再自己判断另一套启动完成日志。
     */
    @Test
    void sandboxGatewayUsesTheDailyEntry() throws Exception {
        String script = Files.readString(Path.of("../scripts/sandbox/start-gateway.sh"));
        assertTrue(script.contains("exec \"$JASAGENT_ROOT/scripts/start.sh\""));
        assertFalse(script.contains("Started GatewayApplication"));
        assertFalse(script.contains("Started JasagentApplication"));
        assertFalse(script.contains("jasagent_start_host_jar"));
    }

    /**
     * 可执行测试脚本存在，并且会跑本轮测试。
     */
    @Test
    void executableTestScriptExists() throws Exception {
        Path script = Path.of("../scripts/test-015-gateway.sh");
        assertTrue(Files.isRegularFile(script));
        assertTrue(Files.isExecutable(script));
        String text = Files.readString(script);
        assertTrue(text.contains("jasagent-gateway"));
        assertTrue(text.contains("找不到"));
        assertFalse(text.contains("password="));
    }
}
