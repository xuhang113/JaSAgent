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

/**
 * 执行和检查点落在本机 MySQL。另一份运行时只凭 executionId 就能读回并继续，不依赖原来的进程内存。
 */
class MysqlResumeTest {
    /**
     * 第一份运行时把 call-rag 挂起后关闭。第二份用另一个家目录按同一 executionId 恢复，状态仍是 SUSPENDED，trace 不变。
     * 副作用：写入本机 jasagent 库。断言失败则测试失败。
     */
    @Test
    void secondRuntimeResumesFromMysql() throws Exception {
        Path home = Files.createTempDirectory("jasagent-mysql-home-a");
        Path otherHome = Files.createTempDirectory("jasagent-mysql-home-b");
        Path workspace = Files.createTempDirectory("jasagent-mysql-ws");
        Files.createDirectories(workspace.resolve("jasagent").resolve("workflows"));
        Files.writeString(workspace.resolve("jasagent").resolve("workflows").resolve("call-rag.json"), """
                {"id":"call-rag","nodes":[{"id":"search","type":"tool","tool":"rag.search"},{"id":"done","type":"end"}]}
                """);
        LocalAuthService auth = new LocalAuthService(home);
        auth.addUser(new AddUser("mysql-user", "test-password-1".toCharArray(), null, false)).toCompletableFuture().join();
        Session session = auth.login("mysql-user", "test-password-1".toCharArray()).toCompletableFuture().join();
        auth.selectWorkspace(session.token(), workspace, List.of(workspace), workspace).toCompletableFuture().join();
        LocalRuntime first = new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true"));
        try {
            Map<String, Object> created = first.submit("mysql-user", session.token(), "call-rag", "hello");
            String executionId = String.valueOf(created.get("executionId"));
            Map<String, Object> suspended = await(first, executionId);
            String traceId = String.valueOf(suspended.get("traceId"));
            assertEquals("SUSPENDED", suspended.get("status"));
            assertEquals(32, traceId.length());
            first.close();
            LocalAuthService secondAuth = new LocalAuthService(otherHome);
            secondAuth.addUser(new AddUser("mysql-user", "test-password-1".toCharArray(), null, false)).toCompletableFuture().join();
            LocalRuntime second = new LocalRuntime(secondAuth, Map.of(PassSettings.ENABLED, "true"));
            try {
                Map<String, Object> resumed = second.resume("mysql-user", executionId);
                assertEquals(executionId, resumed.get("executionId"));
                assertEquals(traceId, resumed.get("traceId"));
                assertEquals("SUSPENDED", resumed.get("status"));
                assertFalse(String.valueOf(resumed.get("logs")).isBlank());
            } finally {
                second.close();
            }
        } finally {
            first.close();
        }
    }

    /**
     * 等到执行进入挂起。超时仍返回最后一次详情，让断言指出状态。
     *
     * @param runtime 正在跑的运行时
     * @param executionId 执行编号
     * @return 详情
     */
    private static Map<String, Object> await(LocalRuntime runtime, String executionId) throws Exception {
        Map<String, Object> last = Map.of();
        for (int attempt = 0; attempt < 50; attempt++) {
            last = runtime.detail("mysql-user", executionId);
            if ("SUSPENDED".equals(last.get("status")) || "FAILED".equals(last.get("status"))) {
                return last;
            }
            Thread.sleep(100);
        }
        return last;
    }
}
