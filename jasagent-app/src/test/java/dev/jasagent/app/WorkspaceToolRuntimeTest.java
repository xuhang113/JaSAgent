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

/**
 * 确认工作区里的列目录可以直接跑完，而删除文件的 shell 会停在待批准并且不碰文件。
 */
class WorkspaceToolRuntimeTest {
    /**
     * list_directory 在通行证开启时仍直接 SUCCEEDED。输出含 fileCount，不含 approval-required。
     * 副作用：创建临时目录、两个文本文件和用户。断言失败则测试失败。
     */
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

    /**
     * 参数像是 rm 的 run_shell_command 必须停在 SUSPENDED，输出含 approval-required，决定是 PENDING，目标文件仍在。
     * 副作用：创建临时目录和 keep.txt。断言失败则测试失败。
     */
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

    /**
     * 建用户、登录、选中工作区，并打开启用通行证的运行时。
     *
     * @param home 家目录
     * @param workspace 工作区
     * @param user 登录名
     * @return 带令牌和运行时的夹具，关闭时会停掉运行时
     */
    private static Opened open(Path home, Path workspace, String user) throws Exception {
        LocalAuthService auth = new LocalAuthService(home);
        auth.addUser(new AddUser(user, "test-password-1".toCharArray(), null, false)).toCompletableFuture().join();
        Session session = auth.login(user, "test-password-1".toCharArray()).toCompletableFuture().join();
        auth.selectWorkspace(session.token(), workspace, List.of(workspace), workspace).toCompletableFuture().join();
        return new Opened(user, session.token(), new LocalRuntime(auth, Map.of(PassSettings.ENABLED, "true")));
    }

    /**
     * 把工作流 JSON 写到工作区的 workflows 目录，文件名用编号加 .json。正文保持原样。
     * 副作用：创建目录并覆盖文件。
     *
     * @param workspace 工作区根路径
     * @param id 工作流编号
     * @param json 工作流原文
     */
    private static void writeWorkflow(Path workspace, String id, String json) throws Exception {
        Path file = workspace.resolve("jasagent").resolve("workflows").resolve(id + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    /**
     * 每 40 毫秒读一次详情，最多 100 次，直到状态匹配。
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
            Thread.sleep(40);
        }
        assertEquals(status, detail.get("status"), String.valueOf(detail));
        return detail;
    }

    /**
     * 一次已登录并选好工作区的运行时夹具。
     *
     * @param user 登录名
     * @param token 会话令牌
     * @param runtime 启用通行证的运行时
     */
    private record Opened(String user, String token, LocalRuntime runtime) implements AutoCloseable {
        /**
         * 关闭夹具持有的运行时，避免虚拟线程和检查点控制器留到下一个测试。
         * 副作用：清空控制器并打断工作线程。
         */
        @Override
        public void close() {
            runtime.close();
        }
    }
}
