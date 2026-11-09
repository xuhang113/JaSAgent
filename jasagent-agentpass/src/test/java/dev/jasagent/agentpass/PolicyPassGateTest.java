package dev.jasagent.agentpass;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对通行证开关、用户策略优先于工作区，以及未命中的只读工具可以直接执行。
 */
class PolicyPassGateTest {
    @TempDir
    Path root;

    /**
     * 没有开关、空配置、空白和 true 都表示通行证开启，只有 false 关闭。断言失败则本测试失败。
     */
    @Test
    void missingEnabledMeansOn() {
        assertTrue(PassSettings.enabled(Map.of()));
        assertTrue(PassSettings.enabled(null));
        assertTrue(PassSettings.enabled(Map.of(PassSettings.ENABLED, "true")));
        assertTrue(PassSettings.enabled(Map.of(PassSettings.ENABLED, "  ")));
        assertFalse(PassSettings.enabled(Map.of(PassSettings.ENABLED, "false")));
    }

    /**
     * 开关写成 yes 这类无法识别的值时要报内部错误。断言失败则本测试失败。
     */
    @Test
    void unknownEnabledValueFails() {
        AgentException error = assertThrows(AgentException.class,
                () -> PassSettings.enabled(Map.of(PassSettings.ENABLED, "yes")));
        assertEquals(ErrorCode.INTERNAL, error.code());
    }

    /**
     * 开关关闭时申请直接跳过，原因是通行证未启用。断言失败则本测试失败。
     */
    @Test
    void disabledGateIsSkipped() throws Exception {
        Pass pass = join(PassSettings.create(Map.of(PassSettings.ENABLED, "false"), userId -> root)
                .acquire(new PassRequest("user-a", "exec-1", "rag.search", null, "hash")));
        assertEquals(PassDecision.SKIPPED, pass.decision());
        assertEquals("agentpass-not-enabled", pass.reason());
    }

    /**
     * 用户没有策略时，即使工作区把工具写成允许，申请仍然等待审批。断言失败则本测试失败。
     */
    @Test
    void unmatchedToolIsPendingAndDoesNotUseWorkspacePolicy() throws Exception {
        Path userDirectory = root.resolve("user-a").resolve("jasagent");
        Path workspacePolicy = root.resolve("workspace").resolve("jasagent").resolve("auth").resolve("policies.yaml");
        Files.createDirectories(workspacePolicy.getParent());
        Files.writeString(workspacePolicy, """
                rules:
                - tool: rag.search
                  decision: ALLOW
                """);
        Pass pass = join(gate(userDirectory).acquire(new PassRequest("user-a", "exec-1", "rag.search", null, "hash")));
        assertEquals(PassDecision.PENDING, pass.decision());
        assertEquals("approval-required", pass.reason());
    }

    /**
     * 用户策略里的拒绝和允许优先于工作区；没有写到的工具仍等待审批。断言失败则本测试失败。
     */
    @Test
    void userAllowAndDenyWin() throws Exception {
        Path userDirectory = root.resolve("user-a").resolve("jasagent");
        writePolicy(userDirectory, """
                rules:
                - tool: rag.search
                  decision: DENY
                - plugin: grounding.default
                  decision: ALLOW
                """);
        Path workspacePolicy = root.resolve("workspace").resolve("jasagent").resolve("auth").resolve("policies.yaml");
        Files.createDirectories(workspacePolicy.getParent());
        Files.writeString(workspacePolicy, """
                rules:
                - tool: rag.search
                  decision: ALLOW
                """);
        PassGate gate = gate(userDirectory);
        Pass denied = join(gate.acquire(new PassRequest("user-a", "exec-1", "rag.search", null, "hash")));
        Pass allowed = join(gate.acquire(new PassRequest("user-a", "exec-1", null, "grounding.default", "hash")));
        Pass missed = join(gate.acquire(new PassRequest("user-a", "exec-1", "other.tool", null, "hash")));
        assertEquals(PassDecision.DENY, denied.decision());
        assertEquals("policy", denied.reason());
        assertEquals(PassDecision.ALLOW, allowed.decision());
        assertEquals(PassDecision.PENDING, missed.decision());
        assertEquals("approval-required", missed.reason());
    }

    /**
     * 策略未提到的列目录和读文件可以直接允许，执行命令仍要等待审批。断言失败则本测试失败。
     */
    @Test
    void unmatchedReadOnlyToolRuns() throws Exception {
        Path userDirectory = root.resolve("user-a").resolve("jasagent");
        PassGate gate = gate(userDirectory);
        Pass listed = join(gate.acquire(new PassRequest("user-a", "exec-1", "list_directory", null, "hash")));
        Pass read = join(gate.acquire(new PassRequest("user-a", "exec-1", "read_file", null, "hash")));
        Pass shell = join(gate.acquire(new PassRequest("user-a", "exec-1", "run_shell_command", null, "hash")));
        assertEquals(PassDecision.ALLOW, listed.decision());
        assertEquals("read-only", listed.reason());
        assertEquals(PassDecision.ALLOW, read.decision());
        assertEquals(PassDecision.PENDING, shell.decision());
        assertEquals("approval-required", shell.reason());
    }

    /**
     * 用户明确拒绝只读工具时，不能因为工具只读就放行。断言失败则本测试失败。
     */
    @Test
    void userDenyStillBlocksReadOnlyTool() throws Exception {
        Path userDirectory = root.resolve("user-a").resolve("jasagent");
        writePolicy(userDirectory, """
                rules:
                - tool: list_directory
                  decision: DENY
                """);
        Pass pass = join(gate(userDirectory).acquire(new PassRequest("user-a", "exec-1", "list_directory", null, "hash")));
        assertEquals(PassDecision.DENY, pass.decision());
        assertEquals("policy", pass.reason());
    }

    /**
     * 策略文件只有空行和注释时，没有规则，申请仍等待审批。断言失败则本测试失败。
     */
    @Test
    void emptyPolicyFileIsPending() throws Exception {
        Path userDirectory = root.resolve("user-a").resolve("jasagent");
        writePolicy(userDirectory, "\n# none\n");
        Pass pass = join(gate(userDirectory).acquire(new PassRequest("user-a", "exec-2", "rag.search", null, "hash")));
        assertEquals(PassDecision.PENDING, pass.decision());
        assertEquals("approval-required", pass.reason());
    }

    /**
     * 工具规则缺少决定时，申请要以内部错误失败。断言失败则本测试失败。
     */
    @Test
    void malformedPolicyFails() throws Exception {
        Path userDirectory = root.resolve("user-a").resolve("jasagent");
        writePolicy(userDirectory, "rules:\n- tool: rag.search\n");
        CompletionException error = assertThrows(CompletionException.class,
                () -> gate(userDirectory).acquire(new PassRequest("user-a", "exec-3", "rag.search", null, "hash"))
                        .toCompletableFuture()
                        .join());
        assertEquals(ErrorCode.INTERNAL, ((AgentException) error.getCause()).code());
    }

    /**
     * 用开启的设置创建一个只看给定用户目录的策略门。
     *
     * @param userDirectory 测试里充当该用户 jasagent 目录的路径
     * @return 按用户策略裁决的门
     */
    private PassGate gate(Path userDirectory) {
        return PassSettings.create(Map.of(), userId -> userDirectory);
    }

    /**
     * 把策略正文写到用户目录的 auth/policies.yaml。副作用是创建 auth 目录并覆盖该文件。
     *
     * @param userDirectory 用户的 jasagent 目录
     * @param yaml 策略全文
     */
    private static void writePolicy(Path userDirectory, String yaml) throws Exception {
        Path file = userDirectory.resolve("auth").resolve("policies.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    /**
     * 在限定时间内取回通行证申请的结果。
     *
     * @param stage 申请或查询返回的阶段
     * @return 阶段成功时的通行证
     */
    private static Pass join(java.util.concurrent.CompletionStage<Pass> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
