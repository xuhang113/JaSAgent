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

class PassGateTest {
    @TempDir
    Path temp;

    @Test
    void enabledDefaultsTrue() {
        assertTrue(PassSettings.enabled(Map.of()));
        assertTrue(PassSettings.enabled(Map.of("jasagent.other", "false")));
        assertTrue(PassSettings.enabled(Map.of("jasagent.agentpass.enabled", "true")));
        assertFalse(PassSettings.enabled(Map.of("jasagent.agentpass.enabled", "false")));
    }

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

    @Test
    void unmatchedIsPending() {
        Pass pass = join(new FilePassGate().acquire(request("aa", "exec-miss", temp.resolve("user"), temp.resolve("workspace"), "weather", null)));
        assertEquals(PassDecision.PENDING, pass.decision());
        assertEquals("approval-required", pass.reason());
    }

    @Test
    void unmatchedSuspendsExecution() {
        FilePassGate gate = new FilePassGate();
        join(gate.acquire(request("aa", "exec-suspend", temp.resolve("user"), temp.resolve("workspace"), "weather", null)));
        assertEquals(ExecutionStatus.SUSPENDED, join(gate.executionStatus("exec-suspend")));
    }

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

    @Test
    void malformedPolicyRejected() throws Exception {
        Path user = temp.resolve("user");
        writePolicy(user, "this is not the policy format\n");
        AgentException error = assertThrows(AgentException.class, () -> join(new FilePassGate().acquire(
                request("aa", "exec-bad", user, temp.resolve("workspace"), "weather", null))));
        assertEquals(ErrorCode.INTERNAL, error.code());
    }

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

    private static PassRequest request(String userId, String executionId, Path userDirectory, Path workspaceDirectory, String toolName, String pluginId) {
        return new PassRequest(userId, executionId, userDirectory, workspaceDirectory, toolName, pluginId, "hash");
    }

    private static void writePolicy(Path jasagentDir, String yaml) throws Exception {
        Path file = jasagentDir.resolve("auth").resolve("policies.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

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
