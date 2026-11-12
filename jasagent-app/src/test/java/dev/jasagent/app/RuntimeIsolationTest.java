package dev.jasagent.app;

import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.runtime.GatewayHeaders;
import dev.jasagent.auth.LocalAuthService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一进程里两个用户的会话不能串，用户和会话所有者不一致时拒绝且不执行。
 */
class RuntimeIsolationTest {
    /**
     * 用户乙向用户甲的会话追加消息时得到明确错误。消息没有写入，模型和工具都没有被调用。
     * 副作用：在本机 MySQL 写入一场会话。断言失败则测试失败。
     */
    @Test
    void mismatchedUserDoesNotExecute() throws Exception {
        Path home = Files.createTempDirectory("jasagent-iso-home");
        LocalAuthService auth = new LocalAuthService(home);
        auth.addUser(new AddUser("owner-a", "test-password-1".toCharArray(), null, false)).toCompletableFuture().join();
        Session owner = auth.login("owner-a", "test-password-1".toCharArray()).toCompletableFuture().join();
        auth.addUser(new AddUser("owner-b", "test-password-1".toCharArray(), owner.token(), true)).toCompletableFuture().join();
        LocalRuntime runtime = new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true"));
        try {
            String sessionId = String.valueOf(runtime.createSession("owner-a", "甲的会话").get("sessionId"));
            int models = runtime.observedModelCalls();
            int tools = runtime.observedToolCalls();
            AgentException error = assertThrows(AgentException.class, () -> runtime.appendMessage(
                    "owner-b",
                    "",
                    sessionId,
                    "不要执行这段",
                    "answer-with-tool"));
            assertEquals(ErrorCode.AUTH, error.code());
            assertEquals(GatewayHeaders.OWNER_MISMATCH, error.getMessage());
            assertEquals(models, runtime.observedModelCalls());
            assertEquals(tools, runtime.observedToolCalls());
            String body = runtime.session("owner-a", sessionId).toString();
            assertFalse(body.contains("不要执行这段"), body);
            LocalRuntime other = new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true"));
            try {
                String again = other.session("owner-a", sessionId).toString();
                assertFalse(again.contains("不要执行这段"), again);
            } finally {
                other.close();
            }
        } finally {
            runtime.close();
        }
    }

    /**
     * 两个用户在同一进程里各写一条消息。上下文、工具记录都只含自己的内容。
     * 副作用：写入两场会话和两条消息。断言失败则测试失败。
     */
    @Test
    void twoUsersStayIsolatedInOneProcess() throws Exception {
        Path home = Files.createTempDirectory("jasagent-iso-two");
        Path workspace = Files.createTempDirectory("jasagent-iso-ws");
        LocalAuthService auth = new LocalAuthService(home);
        auth.addUser(new AddUser("iso-a", "test-password-1".toCharArray(), null, false)).toCompletableFuture().join();
        Session first = auth.login("iso-a", "test-password-1".toCharArray()).toCompletableFuture().join();
        auth.addUser(new AddUser("iso-b", "test-password-1".toCharArray(), first.token(), true)).toCompletableFuture().join();
        auth.selectWorkspace(first.token(), workspace, List.of(workspace), workspace).toCompletableFuture().join();
        Session second = auth.login("iso-b", "test-password-1".toCharArray()).toCompletableFuture().join();
        auth.selectWorkspace(second.token(), workspace, List.of(workspace), workspace).toCompletableFuture().join();
        LocalRuntime runtime = new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true"));
        try {
            String sessionA = String.valueOf(runtime.createSession("iso-a", "甲").get("sessionId"));
            String sessionB = String.valueOf(runtime.createSession("iso-b", "乙").get("sessionId"));
            runtime.appendMessage("iso-a", first.token(), sessionA, "alpha-secret-token", "answer-with-tool");
            runtime.appendMessage("iso-b", second.token(), sessionB, "beta-secret-token", "answer-with-tool");
            String contextA = String.valueOf(runtime.context("iso-a", first.token(), sessionA));
            String contextB = String.valueOf(runtime.context("iso-b", second.token(), sessionB));
            assertTrue(contextA.contains("alpha-secret-token"), contextA);
            assertFalse(contextA.contains("beta-secret-token"), contextA);
            assertTrue(contextB.contains("beta-secret-token"), contextB);
            assertFalse(contextB.contains("alpha-secret-token"), contextB);
            assertTrue(runtime.scopedToolNotes("local", "iso-a", sessionA).stream().noneMatch(line -> line.contains("beta-secret-token")));
            assertTrue(runtime.scopedToolNotes("local", "iso-b", sessionB).stream().noneMatch(line -> line.contains("alpha-secret-token")));
        } finally {
            runtime.close();
        }
    }
}
