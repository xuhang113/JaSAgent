package dev.jasagent.app;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住 agent、命令行和启动脚本的默认端口。agent 使用 18731，对外入口仍是网关的 18721。
 */
class DefaultPortTest {
    /**
     * 读取 application.properties，确认 agent 监听 127.0.0.1:18731，并且不再把 18721 或 8090 写成服务端口。
     * 副作用：只读配置文件。断言失败则测试失败。
     */
    @Test
    void applicationPropertiesUseAgentPort() throws Exception {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(props.contains("server.address=127.0.0.1"), props);
        assertTrue(props.contains("server.port=18731"), props);
        assertFalse(props.contains("server.port=18721"), props);
        assertFalse(props.contains("server.port=8090"), props);
    }

    /**
     * 读取启动类源码，确认默认端口是第一台 agent 的 18731，并且不再把服务端口写成 18721。
     * 副作用：只读 Java 源文件。断言失败则测试失败。
     */
    @Test
    void applicationMainDefaultsToAgentPort() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/jasagent/app/JasagentApplication.java"));
        assertTrue(source.contains("\"server.address\", \"127.0.0.1\""), source);
        assertTrue(source.contains("AgentPorts.PRIMARY"), source);
        assertFalse(source.contains("\"server.port\", \"18721\""), source);
        assertTrue(source.contains("scanBasePackages = {\"dev.jasagent.app\", \"dev.jasagent.controller\"}"), source);
        assertFalse(source.contains("\"dev.jasagent.web\""), source);
    }

    /**
     * 在没有 address 文件、也没有基址和运行目录环境变量的临时家目录里启动探针，确认输出正好是 http://127.0.0.1:18721。
     * 副作用：创建临时目录并启动一个 Java 进程。断言失败则测试失败。
     */
    @Test
    void cliFallsBackTo18721() throws Exception {
        Path home = Files.createTempDirectory("jasagent-port-home");
        assertFalse(Files.exists(home.resolve(".jasagent").resolve("runtime").resolve("address")));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "dev.jasagent.app.CliBaseUrlProbe",
                home.toString());
        builder.environment().remove("JASAGENT_BASE_URL");
        builder.environment().remove("JASAGENT_RUNTIME_DIR");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        assertEquals(0, code, output);
        assertEquals("http://127.0.0.1:18721", output.trim(), output);
    }

    /**
     * 读取 start.sh，确认对外端口缺省仍是 18721，并且拉起的是网关而不是等待应用启动日志。
     * 副作用：只读脚本。断言失败则测试失败。
     */
    @Test
    void startScriptDefaultsToGatewayOn18721() throws Exception {
        String script = Files.readString(Path.of("../scripts/start.sh"));
        assertTrue(script.contains("port=\"${JASAGENT_PORT:-18721}\""), script);
        assertTrue(script.contains("jasagent-gateway"), script);
        assertTrue(script.contains("18731"), script);
        assertTrue(script.contains("18732"), script);
        assertFalse(script.contains("Started JasagentApplication"), script);
    }

    /**
     * 读取 common.sh，确认 address 文件缺失时回退的那一行是 http://127.0.0.1:18721。
     * 副作用：只读脚本。断言失败则测试失败。
     */
    @Test
    void addressFallbackDefaultsTo18721() throws Exception {
        String script = Files.readString(Path.of("../scripts/common.sh"));
        assertTrue(script.contains("line=\"http://127.0.0.1:18721\""), script);
    }
}
