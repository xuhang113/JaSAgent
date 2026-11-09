package dev.jasagent.app;

import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.llm.LlmSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionModelTest {
    @Test
    void eachSessionKeepsItsModelAcrossRestart() throws Exception {
        Path home = Files.createTempDirectory("jasagent-model-home");
        Path workspace = Files.createTempDirectory("jasagent-model-ws");
        LocalAuthService auth = new LocalAuthService(home);
        auth.addUser(new AddUser("model-user", "test-password-1".toCharArray(), null, false)).toCompletableFuture().join();
        Session session = auth.login("model-user", "test-password-1".toCharArray()).toCompletableFuture().join();
        auth.selectWorkspace(session.token(), workspace, List.of(workspace), workspace).toCompletableFuture().join();
        SessionModelCatalog catalog = fixedCatalog();
        LocalRuntime runtime = new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true"), catalog);
        try {
            String first = String.valueOf(runtime.createSession("model-user", "甲").get("sessionId"));
            String second = String.valueOf(runtime.createSession("model-user", "乙").get("sessionId"));
            assertEquals("deepseek-flash", runtime.session("model-user", first).get("model"));
            assertEquals("deepseek", runtime.session("model-user", first).get("modelProvider"));
            runtime.updateModel("model-user", first, "deepseek-v4-pro", "deepseek");
            assertEquals("deepseek-v4-pro", runtime.session("model-user", first).get("model"));
            assertEquals("deepseek-flash", runtime.session("model-user", second).get("model"));
            runtime.close();
            LocalRuntime again = new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true"), catalog);
            try {
                assertEquals("deepseek-v4-pro", again.session("model-user", first).get("model"));
                assertEquals("deepseek-flash", again.session("model-user", second).get("model"));
                Map<String, Object> sent = again.appendMessage("model-user", session.token(), first, "下一句", "answer-with-tool");
                String executionId = String.valueOf(sent.get("executionId"));
                assertTrue(awaitLog(again, "model-user", executionId, "model deepseek deepseek-v4-pro").contains("model deepseek deepseek-v4-pro"));
                assertEquals("deepseek-v4-pro", again.session("model-user", first).get("model"));
                Map<String, Object> other = again.appendMessage(
                        "model-user",
                        session.token(),
                        second,
                        "另一句",
                        "answer-with-tool",
                        "deepseek-flash",
                        "deepseek");
                String otherLogs = awaitLog(again, "model-user", String.valueOf(other.get("executionId")), "model deepseek deepseek-flash");
                assertTrue(otherLogs.contains("model deepseek deepseek-flash"), otherLogs);
                assertFalse(otherLogs.contains("deepseek-v4-pro"), otherLogs);
                assertEquals("deepseek-v4-pro", again.session("model-user", first).get("model"));
            } finally {
                again.close();
            }
        } finally {
            runtime.close();
        }
    }

    @Test
    void otherUserCannotChangeModel() throws Exception {
        Path home = Files.createTempDirectory("jasagent-model-auth-home");
        LocalAuthService auth = new LocalAuthService(home);
        auth.addUser(new AddUser("owner", "test-password-1".toCharArray(), null, false)).toCompletableFuture().join();
        Session owner = auth.login("owner", "test-password-1".toCharArray()).toCompletableFuture().join();
        auth.addUser(new AddUser("other", "test-password-1".toCharArray(), owner.token(), true)).toCompletableFuture().join();
        LocalRuntime runtime = new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true"), fixedCatalog());
        try {
            String sessionId = String.valueOf(runtime.createSession("owner", "自己的").get("sessionId"));
            AgentException error = assertThrows(AgentException.class,
                    () -> runtime.updateModel("other", sessionId, "deepseek-v4-pro", "deepseek"));
            assertEquals(ErrorCode.AUTH, error.code());
            assertEquals("deepseek-flash", runtime.session("owner", sessionId).get("model"));
            assertFalse(runtime.models("owner").get("models").toString().contains("ai-gateway"));
        } finally {
            runtime.close();
        }
    }

    private static SessionModelCatalog fixedCatalog() {
        return new SessionModelCatalog(
                new LlmSettings("placeholder-deepseek-key", false, "", "", "", false, "", Duration.ofSeconds(60)),
                key -> List.of("deepseek-flash", "deepseek-v4-pro"),
                url -> false);
    }

    private static String awaitLog(LocalRuntime runtime, String userId, String executionId, String marker) throws Exception {
        String logs = "";
        for (int attempt = 0; attempt < 80; attempt++) {
            logs = String.valueOf(runtime.detail(userId, executionId).get("logs"));
            if (logs.contains(marker)) {
                return logs;
            }
            Thread.sleep(40);
        }
        assertTrue(logs.contains(marker), logs);
        return logs;
    }
}
