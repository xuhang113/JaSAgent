package dev.jasagent.app;

import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.app.scaling.ScalingSignals;
import dev.jasagent.auth.LocalAuthService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进行中的会话按会话去重，结束状态不计入扩缩容主指标。
 */
class ActiveSessionMetricTest {
    /**
     * 同一会话的多条进行中执行只算一次。SUCCEEDED 不计入。
     */
    @Test
    void runningSessionsAreCountedOnce() throws Exception {
        Path home = Files.createTempDirectory("jasagent-metric");
        LocalRuntime runtime = new LocalRuntime(new LocalAuthService(home), Map.of(PassSettings.ENABLED, "false"));
        assertEquals(0, runtime.activeSessions());
        runtime.stageExecution("e1", "s1", "RUNNING");
        runtime.stageExecution("e2", "s1", "ACCEPTED");
        runtime.stageExecution("e3", "s2", "RUNNING");
        runtime.stageExecution("e4", "s3", "SUCCEEDED");
        assertEquals(2, runtime.activeSessions());
        assertTrue(ScalingSignals.prometheus(runtime.activeSessions(), 0).contains(ScalingSignals.PRIMARY_METRIC + " 2"));
    }
}
