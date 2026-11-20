package dev.jasagent.app;

import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.agentpass.durable.AgentPassLedger;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.auth.LocalAuthService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未命中策略时执行挂起并留下待批。同一用户批准后沿原来的执行编号做完，别人不能代批。
 * 用户目录里的策略文件写成拒绝，也不能代替库里的决定。
 */
class DurablePassResumeTest {
    /**
     * 策略文件拒绝 rag.search，库里没有这条规则。执行挂起，待批在库里，家目录没有 pending 文件。
     * 别人批准被拒绝。所有者允许后，同一次执行成功并带有检索结果。
     * 副作用：创建临时目录、内存库和运行时。断言失败则测试失败。
     */
    @Test
    void approvalResumesOriginalExecution() throws Exception {
        Path home = Files.createTempDirectory("jasagent-durable-home");
        Path workspace = Files.createTempDirectory("jasagent-durable-ws");
        writeCallRag(workspace);
        writeDeny(home, "owner");
        String url = "jdbc:h2:mem:durableResume;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        String password = secret();
        AgentPassLedger ledger = AgentPassLedger.open(url, "sa", password);
        ledger.saveAccount("owner", "tenant-owner");
        ledger.saveAccount("other", "tenant-other");
        LocalAuthService auth = new LocalAuthService(home);
        char[] passwordChars = "test-password-1".toCharArray();
        auth.addUser(new AddUser("owner", passwordChars, null, false)).toCompletableFuture().join();
        Session session = auth.login("owner", passwordChars).toCompletableFuture().join();
        auth.selectWorkspace(session.token(), workspace, List.of(workspace), workspace).toCompletableFuture().join();
        LocalRuntime runtime = new LocalRuntime(auth, durable(url, password));
        try {
            Map<String, Object> created = runtime.submit("owner", session.token(), "call-rag", "hello");
            String executionId = String.valueOf(created.get("executionId"));
            Map<String, Object> suspended = await(runtime, "owner", executionId, "SUSPENDED");
            assertEquals("SUSPENDED", suspended.get("status"));
            String passId = passId(suspended, "rag.search");
            assertEquals("PENDING", ledger.findPending(passId).decision().name());
            assertFalse(Files.isDirectory(home.resolve("owner").resolve("jasagent").resolve("auth").resolve("pending")));
            AgentException denied = org.junit.jupiter.api.Assertions.assertThrows(AgentException.class,
                    () -> runtime.decidePass("other", executionId, passId, "ALLOW"));
            assertEquals(ErrorCode.AUTH, denied.code());
            assertEquals("PENDING", ledger.findPending(passId).decision().name());
            runtime.decidePass("owner", executionId, passId, "ALLOW");
            Map<String, Object> done = await(runtime, "owner", executionId, "SUCCEEDED");
            assertEquals(executionId, done.get("executionId"));
            assertEquals("SUCCEEDED", done.get("status"));
            assertEquals("ALLOW", ledger.findPending(passId).decision().name());
            assertFalse(String.valueOf(done.get("retrievals")).equals("[]"), String.valueOf(done.get("retrievals")));
        } finally {
            runtime.close();
        }
    }

    /**
     * 库里允许 rag.search，目录里的策略文件写成拒绝。执行直接成功，不再挂起。
     * 副作用：创建临时目录和运行时。断言失败则测试失败。
     */
    @Test
    void mysqlAllowIgnoresYamlDeny() throws Exception {
        Path home = Files.createTempDirectory("jasagent-durable-allow-home");
        Path workspace = Files.createTempDirectory("jasagent-durable-allow-ws");
        writeCallRag(workspace);
        writeDeny(home, "owner");
        String url = "jdbc:h2:mem:durableAllow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        String password = secret();
        AgentPassLedger ledger = AgentPassLedger.open(url, "sa", password);
        ledger.saveAccount("owner", "tenant-owner");
        ledger.savePolicy("tenant-owner", "tool", "rag.search", "ALLOW");
        LocalAuthService auth = new LocalAuthService(home);
        char[] passwordChars = "test-password-1".toCharArray();
        auth.addUser(new AddUser("owner", passwordChars, null, false)).toCompletableFuture().join();
        Session session = auth.login("owner", passwordChars).toCompletableFuture().join();
        auth.selectWorkspace(session.token(), workspace, List.of(workspace), workspace).toCompletableFuture().join();
        LocalRuntime runtime = new LocalRuntime(auth, durable(url, password));
        try {
            Map<String, Object> created = runtime.submit("owner", session.token(), "call-rag", "hello");
            String executionId = String.valueOf(created.get("executionId"));
            Map<String, Object> done = await(runtime, "owner", executionId, "SUCCEEDED");
            assertEquals("SUCCEEDED", done.get("status"), String.valueOf(done));
            assertTrue(String.valueOf(done.get("passes")).contains("ALLOW"), String.valueOf(done));
            assertFalse(String.valueOf(done.get("passes")).contains("PENDING"), String.valueOf(done));
        } finally {
            runtime.close();
        }
    }

    /**
     * 组装账本配置。不设置进程的 JDBC 环境变量，执行日志仍用文件。检查点用 sqlite，这样没有本机口令文件也能跑恢复。
     *
     * @param url 内存库地址
     * @param password 这次测试的库口令
     * @return 运行配置
     */
    private static Map<String, String> durable(String url, String password) {
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put(PassSettings.ENABLED, "true");
        configuration.put(PassSettings.DURABLE, "mysql");
        configuration.put(PassSettings.JDBC_URL, url);
        configuration.put(PassSettings.JDBC_USER, "sa");
        configuration.put(PassSettings.JDBC_PASSWORD, password);
        configuration.put("jasagent.egress.enabled", "false");
        configuration.put("JASAGENT_CHECKPOINT", "sqlite");
        return configuration;
    }

    /**
     * 在给定工作区写入 call-rag，节点是 rag.search 然后结束。
     *
     * @param workspace 工作区根路径
     */
    private static void writeCallRag(Path workspace) throws Exception {
        Path file = workspace.resolve("jasagent").resolve("workflows").resolve("call-rag.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"id":"call-rag","nodes":[{"id":"search","type":"tool","tool":"rag.search"},{"id":"done","type":"end"}]}
                """);
    }

    /**
     * 把用户目录里的策略写成拒绝。持久化模式下这文件不能当真相来源。
     *
     * @param home 家目录
     * @param login 登录名
     */
    private static void writeDeny(Path home, String login) throws Exception {
        Path file = home.resolve(login).resolve("jasagent").resolve("auth").resolve("policies.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                rules:
                  - tool: rag.search
                    decision: DENY
                """);
    }

    /**
     * 从详情里取出指定工具的通行证编号。
     *
     * @param detail 执行详情
     * @param tool 工具名
     * @return 通行证编号
     */
    @SuppressWarnings("unchecked")
    private static String passId(Map<String, Object> detail, String tool) {
        List<Map<String, Object>> passes = (List<Map<String, Object>>) detail.get("passes");
        for (Map<String, Object> pass : passes) {
            if (tool.equals(pass.get("name"))) {
                return String.valueOf(pass.get("passId"));
            }
        }
        throw new AssertionError(detail.toString());
    }

    /**
     * 轮询详情直到状态等于期望值。超时则断言失败。
     *
     * @param runtime 被观察的运行时
     * @param userId 所有者登录名
     * @param executionId 执行编号
     * @param status 期望的状态名
     * @return 达到期望状态时的详情
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

    /**
     * 生成只留在这次测试进程里的随机文本。不写进仓库。
     *
     * @return 十六进制文本
     */
    private static String secret() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
