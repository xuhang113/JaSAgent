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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确认运行时关掉之后，用原来的执行编号恢复会到达新的挂起终态，而不是一直停在旧进程里。
 */
class RuntimeResumeFoundationTest {
    /**
     * 第一代运行时把 call-rag 跑到挂起并关闭。第二代在 15 秒内按原编号恢复，状态是 SUSPENDED，检索结果里还没有 sourceScope。
     * 副作用：创建临时目录、用户和两代运行时。超时或断言失败则测试失败。
     */
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
                assertEquals("SUSPENDED", String.valueOf(resumed.get("status")), resumed.toString());
                assertFalse(String.valueOf(resumed.get("retrievals")).contains("sourceScope"), resumed.toString());
                assertNotEquals("RUNNING", String.valueOf(resumed.get("status")));
            } finally {
                second.close();
            }
        } finally {
            first.close();
        }
    }

    /**
     * 每 50 毫秒读一次详情，最多 100 次，直到状态匹配。
     *
     * @param runtime 被观察的运行时
     * @param userId 所有者登录名
     * @param executionId 执行编号
     * @param status 期望的状态名
     * @return 匹配时的详情
     */
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
