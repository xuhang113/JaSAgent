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

/**
 * 确认每个会话记住自己的模型，重启运行时后不串号，别的用户也不能改。
 */
class SessionModelTest {
    /**
     * 两个会话一开始都是 deepseek-flash。只把第一个改成 deepseek-v4-pro，关掉再打开运行时后仍然各自保持。发消息时日志里的 model 行对应该会话，不出现另一个会话的模型。
     * 副作用：创建临时目录、用户、会话和执行。断言失败则测试失败。
     */
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

    /**
     * 其他用户修改会话模型时得到 AUTH，所有者看到的模型仍是 deepseek-flash，模型列表里没有 ai-gateway。
     * 副作用：创建所有者和另一名用户。断言失败则测试失败。
     */
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

    /**
     * 造一个不访问网络的目录：DeepSeek 只有 flash 和 v4-pro，Gateway 永远不可达。
     *
     * @return 测试用的模型目录
     */
    private static SessionModelCatalog fixedCatalog() {
        return new SessionModelCatalog(
                new LlmSettings("placeholder-deepseek-key", false, "", "", "", false, "", Duration.ofSeconds(60)),
                key -> List.of("deepseek-flash", "deepseek-v4-pro"),
                url -> false);
    }

    /**
     * 轮询执行详情里的日志，直到出现指定片段。
     *
     * @param runtime 被观察的运行时
     * @param userId 所有者登录名
     * @param executionId 执行编号
     * @param marker 日志里必须出现的文字
     * @return 含该片段的日志
     */
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
