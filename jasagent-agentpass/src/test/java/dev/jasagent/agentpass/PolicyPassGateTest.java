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

class PolicyPassGateTest {
    @TempDir
    Path root;

    @Test
    void missingEnabledMeansOn() {
        assertTrue(PassSettings.enabled(Map.of()));
        assertTrue(PassSettings.enabled(null));
        assertTrue(PassSettings.enabled(Map.of(PassSettings.ENABLED, "true")));
        assertTrue(PassSettings.enabled(Map.of(PassSettings.ENABLED, "  ")));
        assertFalse(PassSettings.enabled(Map.of(PassSettings.ENABLED, "false")));
    }

    @Test
    void unknownEnabledValueFails() {
        AgentException error = assertThrows(AgentException.class,
                () -> PassSettings.enabled(Map.of(PassSettings.ENABLED, "yes")));
        assertEquals(ErrorCode.INTERNAL, error.code());
    }

    @Test
    void disabledGateIsSkipped() throws Exception {
        Pass pass = join(PassSettings.create(Map.of(PassSettings.ENABLED, "false"), userId -> root)
                .acquire(new PassRequest("user-a", "exec-1", "rag.search", null, "hash")));
        assertEquals(PassDecision.SKIPPED, pass.decision());
        assertEquals("agentpass-not-enabled", pass.reason());
    }

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

    @Test
    void emptyPolicyFileIsPending() throws Exception {
        Path userDirectory = root.resolve("user-a").resolve("jasagent");
        writePolicy(userDirectory, "\n# none\n");
        Pass pass = join(gate(userDirectory).acquire(new PassRequest("user-a", "exec-2", "rag.search", null, "hash")));
        assertEquals(PassDecision.PENDING, pass.decision());
        assertEquals("approval-required", pass.reason());
    }

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

    private PassGate gate(Path userDirectory) {
        return PassSettings.create(Map.of(), userId -> userDirectory);
    }

    private static void writePolicy(Path userDirectory, String yaml) throws Exception {
        Path file = userDirectory.resolve("auth").resolve("policies.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    private static Pass join(java.util.concurrent.CompletionStage<Pass> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
