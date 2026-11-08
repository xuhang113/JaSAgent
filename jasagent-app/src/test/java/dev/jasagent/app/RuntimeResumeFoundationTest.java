package dev.jasagent.app;

import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.LocalAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeResumeFoundationTest {
    @Test
    @Timeout(value = 30)
    void resumeAfterRuntimeCloseReachesNewTerminal() throws Exception {
        Path home = Files.createTempDirectory("jasagent-resume-home");
        Path workspace = Files.createTempDirectory("jasagent-resume-ws");
        Path workflows = workspace.resolve("jasagent").resolve("workflows");
        Files.createDirectories(workflows);
        Files.writeString(workflows.resolve("call-rag.json"), """
                {"id":"call-rag","nodes":[{"id":"search","type":"tool","tool":"rag.search"},{"id":"done","type":"end"}]}
                """);
        LocalAuthService auth = new LocalAuthService(home);
        auth.addUser(new AddUser("resume-user", "test-password-1".toCharArray(), null, false)).toCompletableFuture().join();
        Session session = auth.login("resume-user", "test-password-1".toCharArray()).toCompletableFuture().join();
        auth.selectWorkspace(session.token(), workspace, List.of(workspace), workspace).toCompletableFuture().join();
        LocalRuntime first = new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true"));
        try {
            Map<String, Object> created = first.submit("resume-user", session.token(), "call-rag", "hello");
            String executionId = String.valueOf(created.get("executionId"));
            await(first, "resume-user", executionId, "SUSPENDED");
            first.close();
            LocalRuntime second = new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true"));
            try {
                ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
                Future<Map<String, Object>> pending = pool.submit(() -> second.resume("resume-user", executionId));
                Map<String, Object> resumed;
                try {
                    resumed = pending.get(15, TimeUnit.SECONDS);
                } catch (TimeoutException ex) {
                    pending.cancel(true);
                    throw new AssertionError("关掉运行时后，用原 executionId 恢复没有到达新的终态", ex);
                } finally {
                    pool.shutdownNow();
                }
                assertEquals(executionId, String.valueOf(resumed.get("executionId")), resumed.toString());
                String status = String.valueOf(resumed.get("status"));
                assertNotEquals("SUSPENDED", status, resumed.toString());
                assertTrue("SUCCEEDED".equals(status) || "FAILED".equals(status), resumed.toString());
            } finally {
                second.close();
            }
        } finally {
            first.close();
        }
    }

    private static Map<String, Object> await(LocalRuntime runtime, String userId, String executionId, String status) throws Exception {
        Map<String, Object> detail = Map.of();
        for (int attempt = 0; attempt < 100; attempt++) {
            detail = runtime.detail(userId, executionId);
            if (status.equals(detail.get("status"))) {
                return detail;
            }
            Thread.sleep(50);
        }
        assertEquals(status, detail.get("status"), String.valueOf(detail));
        return detail;
    }
}
