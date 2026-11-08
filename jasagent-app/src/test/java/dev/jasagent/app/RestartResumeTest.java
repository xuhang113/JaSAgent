package dev.jasagent.app;

import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.LocalAuthService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartResumeTest {
    @Test
    void suspendedExecutionResumesWithSavedTraceId() throws Exception {
        Path home = Files.createTempDirectory("jasagent-restart-home");
        Path workspace = Files.createTempDirectory("jasagent-restart-ws");
        LocalAuthService auth = new LocalAuthService(home);
        auth.addUser(new AddUser("restart-user", "test-password-1".toCharArray(), null, false)).toCompletableFuture().join();
        Session session = auth.login("restart-user", "test-password-1".toCharArray()).toCompletableFuture().join();
        auth.selectWorkspace(session.token(), workspace, List.of(workspace), workspace).toCompletableFuture().join();
        LocalRuntime first = new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true"));
        try {
            Map<String, Object> created = first.submit("restart-user", session.token(), "answer-with-tool", "hi");
            String executionId = String.valueOf(created.get("executionId"));
            Map<String, Object> suspended = await(first, "restart-user", executionId, "SUSPENDED");
            String traceId = String.valueOf(suspended.get("traceId"));
            assertEquals(32, traceId.length());
            assertFalse(traceId.contains("-"));
            first.close();
            LocalRuntime second = new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true"));
            try {
                Map<String, Object> resumed = second.resume("restart-user", executionId);
                assertEquals(executionId, resumed.get("executionId"));
                assertEquals(traceId, resumed.get("traceId"));
                assertEquals("SUSPENDED", resumed.get("status"));
                List<String> children = second.observedChildTraceIds();
                assertFalse(children.isEmpty(), resumed.toString());
                for (String child : children) {
                    assertEquals(traceId, child);
                }
            } finally {
                second.close();
            }
        } finally {
            first.close();
        }
    }

    @Test
    void missingModelKeyDoesNotSucceed() throws Exception {
        Path home = Files.createTempDirectory("jasagent-offline-home");
        Path workspace = Files.createTempDirectory("jasagent-offline-ws");
        Files.createDirectories(workspace.resolve("jasagent").resolve("workflows"));
        Files.writeString(workspace.resolve("jasagent").resolve("workflows").resolve("show-support.json"), """
                {"id":"show-support","nodes":[{"id":"now","type":"tool","tool":"clock"},{"id":"act","type":"react","maxSteps":2,"tools":[],"grounding":false},{"id":"done","type":"end"}]}
                """);
        LocalAuthService auth = new LocalAuthService(home);
        auth.addUser(new AddUser("offline-user", "test-password-1".toCharArray(), null, false)).toCompletableFuture().join();
        Session session = auth.login("offline-user", "test-password-1".toCharArray()).toCompletableFuture().join();
        auth.selectWorkspace(session.token(), workspace, List.of(workspace), workspace).toCompletableFuture().join();
        LocalRuntime runtime = new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true"));
        try {
            Map<String, Object> created = runtime.submit("offline-user", session.token(), "show-support", "现在几点");
            String executionId = String.valueOf(created.get("executionId"));
            Map<String, Object> detail = await(runtime, "offline-user", executionId, "FAILED");
            assertEquals("FAILED", detail.get("status"));
            assertEquals("OFFLINE", detail.get("errorCode"));
            assertFalse(String.valueOf(detail.get("output")).contains("已收到"));
            assertFalse(String.valueOf(detail.get("logs")).contains("已收到"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void healthSeesUnreachableStorage() {
        Path home = Path.of(System.getProperty("java.io.tmpdir"), "jasagent-storage-down-" + System.nanoTime());
        LocalAuthService auth = new LocalAuthService(home);
        LocalRuntime runtime = new LocalRuntime(auth, Map.of(
                "JASAGENT_JDBC_URL", "jdbc:postgresql://127.0.0.1:1/jasagent?connectTimeout=1&socketTimeout=1&loginTimeout=1"));
        assertFalse(runtime.storageReachable());
        runtime.close();
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
