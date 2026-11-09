package dev.jasagent.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确认 JDBC 指向一个连不上的地址时，独立进程里的 /health 会报告存储 down。
 */
class HealthStorageDownTest {
    /**
     * 启动 HealthDownProbe，把 JDBC 指到 127.0.0.1:1，并要求 45 秒内以 0 退出。输出的最后一条 HEALTH_RESULT 必须是 503，且 status 和 storage 都是 down。
     * 副作用：创建临时家目录和日志文件，并可能强制结束超时进程。断言失败则测试失败。
     */
    @Test
    @Timeout(value = 60)
    void healthIsDownWhenStorageUnreachable() throws Exception {
        Path home = Files.createTempDirectory("jasagent-health-down");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "dev.jasagent.app.HealthDownProbe",
                home.toString());
        builder.environment().put(
                "JASAGENT_JDBC_URL",
                "jdbc:postgresql://127.0.0.1:1/jasagent?connectTimeout=1&socketTimeout=1&loginTimeout=1");
        Path log = Files.createTempFile("jasagent-health-down", ".log");
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(45, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(finished, output);
        assertEquals(0, process.exitValue(), output);
        int mark = output.lastIndexOf("HEALTH_RESULT ");
        assertTrue(mark >= 0, output);
        String line = output.substring(mark).trim();
        assertTrue(line.startsWith("HEALTH_RESULT 503 "), line);
        assertTrue(line.contains("\"status\":\"down\""), line);
        assertTrue(line.contains("\"storage\":\"down\""), line);
    }
}
