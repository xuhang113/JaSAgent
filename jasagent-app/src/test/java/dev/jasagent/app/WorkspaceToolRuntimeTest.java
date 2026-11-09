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

class WorkspaceToolRuntimeTest {
    @Test
    void listDirectoryRunsWithoutSuspending() throws Exception {
        Path home = Files.createTempDirectory("jasagent-files-home");
        Path workspace = Files.createTempDirectory("jasagent-files-ws");
        Files.writeString(workspace.resolve("one.txt"), "1");
        Files.writeString(workspace.resolve("two.txt"), "2");
        writeWorkflow(workspace, "list-files", """
                {"id":"list-files","nodes":[{"id":"ls","type":"tool","tool":"list_directory"},{"id":"done","type":"end"}]}
                """);
        try (Opened opened = open(home, workspace, "files-user")) {
            Map<String, Object> created = opened.runtime.submit(opened.user, opened.token, "list-files", "{\"path\":\".\"}");
            Map<String, Object> detail = await(opened.runtime, opened.user, String.valueOf(created.get("executionId")), "SUCCEEDED");
            String output = String.valueOf(detail.get("output"));
            assertTrue(output.contains("fileCount:"), output);
            assertFalse(output.contains("approval-required"), output);
            assertEquals("SUCCEEDED", detail.get("status"));
        }
    }

    @Test
    void dangerousShellStaysPending() throws Exception {
        Path home = Files.createTempDirectory("jasagent-shell-home");
        Path workspace = Files.createTempDirectory("jasagent-shell-ws");
        Files.writeString(workspace.resolve("keep.txt"), "stay");
        writeWorkflow(workspace, "run-shell", """
                {"id":"run-shell","nodes":[{"id":"sh","type":"tool","tool":"run_shell_command"},{"id":"done","type":"end"}]}
                """);
        try (Opened opened = open(home, workspace, "shell-user")) {
            Map<String, Object> created = opened.runtime.submit(opened.user, opened.token, "run-shell", "rm keep.txt");
            Map<String, Object> detail = await(opened.runtime, opened.user, String.valueOf(created.get("executionId")), "SUSPENDED");
            assertEquals("SUSPENDED", detail.get("status"));
            assertTrue(String.valueOf(detail.get("output")).contains("approval-required"), String.valueOf(detail));
            assertTrue(String.valueOf(detail).contains("\"decision\":\"PENDING\"") || String.valueOf(detail).contains("decision=PENDING"), String.valueOf(detail));
            assertTrue(Files.exists(workspace.resolve("keep.txt")));
        }
    }

    private static Opened open(Path home, Path workspace, String user) throws Exception {
        LocalAuthService auth = new LocalAuthService(home);
        auth.addUser(new AddUser(user, "test-password-1".toCharArray(), null, false)).toCompletableFuture().join();
        Session session = auth.login(user, "test-password-1".toCharArray()).toCompletableFuture().join();
        auth.selectWorkspace(session.token(), workspace, List.of(workspace), workspace).toCompletableFuture().join();
        return new Opened(user, session.token(), new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true")));
    }

    private static void writeWorkflow(Path workspace, String id, String json) throws Exception {
        Path file = workspace.resolve("jasagent").resolve("workflows").resolve(id + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    private static Map<String, Object> await(LocalRuntime runtime, String userId, String executionId, String status) throws Exception {
        Map<String, Object> detail = Map.of();
        for (int attempt = 0; attempt < 100; attempt++) {
            detail = runtime.detail(userId, executionId);
            if (status.equals(detail.get("status"))) {
                return detail;
            }
            Thread.sleep(40);
        }
        assertEquals(status, detail.get("status"), String.valueOf(detail));
        return detail;
    }

    private record Opened(String user, String token, LocalRuntime runtime) implements AutoCloseable {
        @Override
        public void close() {
            runtime.close();
        }
    }
}
