package dev.jasagent.agentpass;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.execution.ExecutionStatus;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassApproval;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对文件门的策略命中、待审批落盘、审批权限，以及工作区策略不能放宽用户决定。
 */
class PassGateTest {
    @TempDir
    Path temp;

    /**
     * 无关配置和显式 true 都保持通行证开启，显式 false 才关闭。断言失败则本测试失败。
     */
    @Test
    void enabledDefaultsTrue() {
        assertTrue(PassSettings.enabled(Map.of()));
        assertTrue(PassSettings.enabled(Map.of("jasagent.other", "false")));
        assertTrue(PassSettings.enabled(Map.of("jasagent.agentpass.enabled", "true")));
        assertFalse(PassSettings.enabled(Map.of("jasagent.agentpass.enabled", "false")));
    }

    /**
     * 门关闭时即使策略拒绝该工具，申请也只记为未启用，并且不挂起执行。断言失败则本测试失败。
     */
    @Test
    void disabledPassIsSkipped() throws Exception {
        FilePassGate gate = new FilePassGate(false);
        Path user = temp.resolve("user");
        writePolicy(user, """
                rules:
                  - tool: weather
                    decision: DENY
                """);
        Pass pass = join(gate.acquire(request("aa", "exec-skip", user, temp.resolve("workspace"), "weather", null)));
        assertEquals(PassDecision.SKIPPED, pass.decision());
        assertEquals("agentpass-not-enabled", pass.reason());
        assertNull(join(gate.executionStatus("exec-skip")));
    }

    /**
     * 策略把工具写成允许时，申请原因来自策略并直接允许。断言失败则本测试失败。
     */
    @Test
    void matchedToolAllow() throws Exception {
        Path user = temp.resolve("user");
        writePolicy(user, """
                rules:
                  - tool: weather
                    decision: ALLOW
                """);
        Pass pass = join(new FilePassGate().acquire(request("aa", "exec-allow", user, temp.resolve("workspace"), "weather", null)));
        assertEquals(PassDecision.ALLOW, pass.decision());
        assertEquals("policy", pass.reason());
    }

    /**
     * 策略拒绝工具时返回拒绝，且这次执行不会被挂起。断言失败则本测试失败。
     */
    @Test
    void matchedToolDenyDoesNotSuspend() throws Exception {
        Path user = temp.resolve("user");
        writePolicy(user, """
                rules:
                  - tool: shell
                    decision: DENY
                """);
        FilePassGate gate = new FilePassGate();
        Pass pass = join(gate.acquire(request("aa", "exec-deny", user, temp.resolve("workspace"), "shell", null)));
        assertEquals(PassDecision.DENY, pass.decision());
        assertNull(join(gate.executionStatus("exec-deny")));
    }

    /**
     * 按插件标识命中的拒绝同样生效。断言失败则本测试失败。
     */
    @Test
    void matchedPluginDeny() throws Exception {
        Path user = temp.resolve("user");
        writePolicy(user, """
                rules:
                  - plugin: grounding.default
                    decision: DENY
                """);
        Pass pass = join(new FilePassGate().acquire(request("aa", "exec-plugin", user, temp.resolve("workspace"), null, "grounding.default")));
        assertEquals(PassDecision.DENY, pass.decision());
    }

    /**
     * 同一工具有多条规则时采用文件中的第一条，后面的允许不能翻案。断言失败则本测试失败。
     */
    @Test
    void firstRuleWins() throws Exception {
        Path user = temp.resolve("user");
        writePolicy(user, """
                rules:
                  - tool: weather
                    decision: DENY
                  - tool: weather
                    decision: ALLOW
                """);
        Pass pass = join(new FilePassGate().acquire(request("aa", "exec-first", user, temp.resolve("workspace"), "weather", null)));
        assertEquals(PassDecision.DENY, pass.decision());
    }

    /**
     * 没有策略文件时，申请等待审批，原因是需要人工批准。断言失败则本测试失败。
     */
    @Test
    void unmatchedIsPending() {
        Pass pass = join(new FilePassGate().acquire(request("aa", "exec-miss", temp.resolve("user"), temp.resolve("workspace"), "weather", null)));
        assertEquals(PassDecision.PENDING, pass.decision());
        assertEquals("approval-required", pass.reason());
    }

    /**
     * 未命中策略的申请要把这次执行标成挂起。断言失败则本测试失败。
     */
    @Test
    void unmatchedSuspendsExecution() {
        FilePassGate gate = new FilePassGate();
        join(gate.acquire(request("aa", "exec-suspend", temp.resolve("user"), temp.resolve("workspace"), "weather", null)));
        assertEquals(ExecutionStatus.SUSPENDED, join(gate.executionStatus("exec-suspend")));
    }

    /**
     * 等待中的通行证只出现在申请用户的待审批列表里，文件权限仅属主可读写，新的门也能从磁盘读回。断言失败则本测试失败。
     */
    @Test
    void unmatchedIsRecordedForThatUser() throws Exception {
        Path user = temp.resolve("user");
        FilePassGate gate = new FilePassGate();
        Pass pass = join(gate.acquire(request("aa", "exec-record", user, temp.resolve("workspace"), "weather", null)));
        List<Pass> own = join(gate.pending("aa", user));
        assertEquals(1, own.size());
        assertEquals(pass.passId(), own.get(0).passId());
        assertEquals(PassDecision.PENDING, own.get(0).decision());
        assertTrue(join(gate.pending("bb", user)).isEmpty());
        Path file = user.resolve("auth").resolve("pending").resolve(pass.passId());
        assertEquals(Set.of(OWNER_READ, OWNER_WRITE), Files.getPosixFilePermissions(file));
        List<Pass> again = join(new FilePassGate().pending("aa", user));
        assertEquals(pass.passId(), again.get(0).passId());
        assertEquals(PassDecision.PENDING, again.get(0).decision());
    }

    /**
     * 别的用户不能批准这条通行证，原记录仍保持等待。断言失败则本测试失败。
     */
    @Test
    void otherUserCannotApprove() {
        Path user = temp.resolve("user");
        FilePassGate gate = new FilePassGate();
        Pass pass = join(gate.acquire(request("aa", "exec-own", user, temp.resolve("workspace"), "weather", null)));
        AgentException error = assertThrows(AgentException.class, () -> join(gate.decide(
                new PassApproval("bb", "exec-own", pass.passId(), PassDecision.ALLOW, user))));
        assertEquals(ErrorCode.AUTH, error.code());
        assertEquals(PassDecision.PENDING, join(gate.pending("aa", user)).get(0).decision());
    }

    /**
     * 审批里的执行标识必须和申请时一致，否则拒绝批准且记录仍等待。断言失败则本测试失败。
     */
    @Test
    void approvalExecutionIdMustMatch() {
        Path user = temp.resolve("user");
        FilePassGate gate = new FilePassGate();
        Pass pass = join(gate.acquire(request("aa", "exec-own", user, temp.resolve("workspace"), "weather", null)));
        AgentException error = assertThrows(AgentException.class, () -> join(gate.decide(
                new PassApproval("aa", "exec-other", pass.passId(), PassDecision.ALLOW, user))));
        assertEquals(ErrorCode.AUTH, error.code());
        assertEquals(PassDecision.PENDING, join(gate.pending("aa", user)).get(0).decision());
    }

    /**
     * 工作区把工具写成允许也不能推翻用户的拒绝，未在用户策略中的工具仍等待审批。断言失败则本测试失败。
     */
    @Test
    void workspacePolicyCannotRelax() throws Exception {
        Path user = temp.resolve("user");
        Path workspace = temp.resolve("workspace");
        writePolicy(user, """
                rules:
                  - tool: shell
                    decision: DENY
                """);
        writePolicy(workspace, """
                rules:
                  - tool: shell
                    decision: ALLOW
                  - tool: weather
                    decision: ALLOW
                """);
        FilePassGate gate = new FilePassGate();
        Pass denied = join(gate.acquire(request("aa", "exec-shell", user, workspace, "shell", null)));
        Pass missed = join(gate.acquire(request("aa", "exec-weather", user, workspace, "weather", null)));
        assertEquals(PassDecision.DENY, denied.decision());
        assertEquals(PassDecision.PENDING, missed.decision());
        assertEquals("approval-required", missed.reason());
    }

    /**
     * 两个用户对同一工具可以有相反策略，申请各自遵循自己的文件。断言失败则本测试失败。
     */
    @Test
    void policiesStayWithEachUser() throws Exception {
        Path userA = temp.resolve("user-a");
        Path userB = temp.resolve("user-b");
        writePolicy(userA, """
                rules:
                  - tool: weather
                    decision: ALLOW
                """);
        writePolicy(userB, """
                rules:
                  - tool: weather
                    decision: DENY
                """);
        FilePassGate gate = new FilePassGate();
        Pass allow = join(gate.acquire(request("aa", "exec-a", userA, temp.resolve("workspace"), "weather", null)));
        Pass deny = join(gate.acquire(request("bb", "exec-b", userB, temp.resolve("workspace"), "weather", null)));
        assertEquals(PassDecision.ALLOW, allow.decision());
        assertEquals(PassDecision.DENY, deny.decision());
    }

    /**
     * 不是规则格式的策略文件要让申请以内部错误失败。断言失败则本测试失败。
     */
    @Test
    void malformedPolicyRejected() throws Exception {
        Path user = temp.resolve("user");
        writePolicy(user, "this is not the policy format\n");
        AgentException error = assertThrows(AgentException.class, () -> join(new FilePassGate().acquire(
                request("aa", "exec-bad", user, temp.resolve("workspace"), "weather", null))));
        assertEquals(ErrorCode.INTERNAL, error.code());
    }

    /**
     * 已经允许的通行证不能再改成拒绝，磁盘上仍只保留允许。断言失败则本测试失败。
     */
    @Test
    void decidedPassCannotBeApprovedAgain() throws Exception {
        Path user = temp.resolve("user");
        FilePassGate gate = new FilePassGate();
        Pass pending = join(gate.acquire(request("aa", "exec-once", user, temp.resolve("workspace"), "weather", null)));
        Pass allowed = join(gate.decide(new PassApproval("aa", "exec-once", pending.passId(), PassDecision.ALLOW, user)));
        assertEquals(PassDecision.ALLOW, allowed.decision());
        AgentException error = assertThrows(AgentException.class, () -> join(gate.decide(
                new PassApproval("aa", "exec-once", pending.passId(), PassDecision.DENY, user))));
        assertEquals(ErrorCode.INTERNAL, error.code());
        String body = Files.readString(user.resolve("auth").resolve("pending").resolve(pending.passId()));
        assertTrue(body.contains("decision=ALLOW"));
        assertFalse(body.contains("decision=DENY"));
    }

    /**
     * 组装一条带固定参数摘要的通行证申请。
     *
     * @param userId 申请用户
     * @param executionId 所属执行
     * @param userDirectory 用户目录
     * @param workspaceDirectory 工作区目录，文件门的策略不读取这里
     * @param toolName 工具名，按插件申请时为空
     * @param pluginId 插件标识，按工具申请时为空
     * @return 可交给文件门的申请
     */
    private static PassRequest request(String userId, String executionId, Path userDirectory, Path workspaceDirectory, String toolName, String pluginId) {
        return new PassRequest(userId, executionId, userDirectory, workspaceDirectory, toolName, pluginId, "hash");
    }

    /**
     * 把策略正文写到目录下的 auth/policies.yaml。副作用是创建 auth 目录并覆盖该文件。
     *
     * @param jasagentDir 用户或工作区的 jasagent 目录
     * @param yaml 策略全文
     */
    private static void writePolicy(Path jasagentDir, String yaml) throws Exception {
        Path file = jasagentDir.resolve("auth").resolve("policies.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    /**
     * 在限定时间内取回异步结果，并把包装异常还原成原来的运行时异常；等待被中断时重新置上中断标记。
     *
     * @param stage 待等待的异步阶段
     * @return 阶段成功时的结果
     */
    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
