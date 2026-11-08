package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.execution.ExecutionStatus;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassApproval;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.AgentPlugin;
import dev.jasagent.api.plugin.HallucinationGuard;
import dev.jasagent.api.plugin.PluginCall;
import dev.jasagent.api.plugin.PluginCallEntry;
import dev.jasagent.api.plugin.PluginContext;
import dev.jasagent.api.plugin.PluginFailure;
import dev.jasagent.api.plugin.PluginPhase;
import dev.jasagent.api.plugin.PluginResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginHostTest {
    @TempDir
    Path temp;

    @Test
    void serviceLoaderLoadsGroundingDefault() {
        ServicePluginHost host = ServicePluginHost.load(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore());
        assertTrue(host.ids().contains("grounding.default"));
    }

    @Test
    void groundingDefaultRunsOnAfterModel() {
        RecordingGuard guard = new RecordingGuard();
        ServicePluginHost host = ServicePluginHost.load(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore());
        PluginResult result = join(host.invoke(pluginCall("exec-g", "grounding.default", PluginPhase.afterModel, "query", "model-text", null), new PluginContext(guard)));
        assertEquals(List.of("exec-g|model-text"), guard.seen);
        assertTrue(result.executed());
    }

    @Test
    void groundingDefaultSkipsGuardOnOtherPhase() {
        RecordingGuard guard = new RecordingGuard();
        ServicePluginHost host = ServicePluginHost.load(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore());
        PluginResult result = join(host.invoke(pluginCall("exec-before", "grounding.default", PluginPhase.beforeModel, "query", "model-text", null), new PluginContext(guard)));
        assertTrue(guard.seen.isEmpty());
        assertTrue(result.executed());
    }

    @Test
    void pluginAcquiresPassBeforeBody() {
        AtomicInteger hits = new AtomicInteger();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW, () -> assertEquals(0, hits.get()));
        ServicePluginHost host = new ServicePluginHost(gate, new MemoryPluginCallStore(), List.of(echo(hits)));
        PluginResult result = join(host.invoke(pluginCall("exec-plugin", "echo", PluginPhase.beforeTool, "query", "model-text", null), new PluginContext(null)));
        assertEquals(1, gate.requests.size());
        PassRequest request = gate.requests.get(0);
        assertEquals("aa", request.userId());
        assertEquals("exec-plugin", request.executionId());
        assertEquals("echo", request.pluginId());
        assertEquals(1, hits.get());
        assertTrue(result.executed());
    }

    @Test
    void pluginArgumentHashMatchesIndependentSha256() throws Exception {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        ServicePluginHost host = new ServicePluginHost(gate, new MemoryPluginCallStore(), List.of(echo(new AtomicInteger())));
        join(host.invoke(pluginCall("exec-hash", "echo", PluginPhase.afterModel, "query", "model-text", null), new PluginContext(null)));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest("afterModel\nquery\nmodel-text".getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(hashed), gate.requests.get(0).argumentHash());
    }

    @Test
    void denyDoesNotEnterPlugin() {
        AtomicInteger hits = new AtomicInteger();
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.DENY), new MemoryPluginCallStore(), List.of(echo(hits)));
        PluginResult result = join(host.invoke(pluginCall("exec-deny", "echo", PluginPhase.beforeTool, "query", "", null), new PluginContext(null)));
        assertEquals(0, hits.get());
        assertEquals(PassDecision.DENY, result.pass().decision());
        assertFalse(result.executed());
    }

    @Test
    void pendingDoesNotEnterPlugin() {
        AtomicInteger hits = new AtomicInteger();
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.PENDING), new MemoryPluginCallStore(), List.of(echo(hits)));
        PluginResult result = join(host.invoke(pluginCall("exec-pending", "echo", PluginPhase.beforeTool, "query", "", null), new PluginContext(null)));
        assertEquals(0, hits.get());
        assertEquals(PassDecision.PENDING, result.pass().decision());
        assertFalse(result.executed());
    }

    @Test
    void pluginFailureContinuesByDefault() {
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore(), List.of(broken()));
        PluginResult result = join(host.invoke(pluginCall("exec-fail", "broken", PluginPhase.afterTool, "query", "", null), new PluginContext(null)));
        assertTrue(result.failed());
        assertFalse(result.executed());
    }

    @Test
    void pluginFailureFailStopsCall() {
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore(), List.of(broken()));
        AgentException error = assertThrows(AgentException.class, () -> join(host.invoke(
                pluginCall("exec-stop", "broken", PluginPhase.afterTool, "query", "", PluginFailure.FAIL),
                new PluginContext(null))));
        assertEquals(ErrorCode.PLUGIN, error.code());
    }

    @Test
    void skippedExecutesPlugin() {
        AtomicInteger hits = new AtomicInteger();
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.SKIPPED), new MemoryPluginCallStore(), List.of(echo(hits)));
        PluginResult result = join(host.invoke(pluginCall("exec-skip", "echo", PluginPhase.onNodeStart, "query", "", null), new PluginContext(null)));
        assertEquals(1, hits.get());
        assertEquals(PassDecision.SKIPPED, result.pass().decision());
    }

    @Test
    void duplicatePluginIdFailsLoad() {
        AgentException error = assertThrows(AgentException.class, () -> new ServicePluginHost(
                new ScriptedPassGate(PassDecision.ALLOW),
                new MemoryPluginCallStore(),
                List.of(echo(new AtomicInteger()), echo(new AtomicInteger()))));
        assertEquals(ErrorCode.PLUGIN, error.code());
    }

    @Test
    void allowPendingExecutesOriginalExecution() {
        AtomicInteger hits = new AtomicInteger();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.PENDING);
        ServicePluginHost host = new ServicePluginHost(gate, new MemoryPluginCallStore(), List.of(echo(hits)));
        PluginContext context = new PluginContext(null);
        PluginResult pending = join(host.invoke(pluginCall("exec-plugin", "echo", PluginPhase.afterModel, "query", "model-text", null), context));
        assertEquals(0, hits.get());
        PluginResult allowed = join(host.completePending(
                new PassApproval("aa", "exec-plugin", pending.pass().passId(), PassDecision.ALLOW, temp.resolve("user")),
                context));
        assertEquals(1, hits.get());
        assertEquals("exec-plugin", allowed.executionId());
        assertTrue(allowed.executed());
        assertEquals(PassDecision.ALLOW, allowed.pass().decision());
    }

    @Test
    void denyPendingDoesNotEnterPlugin() {
        AtomicInteger hits = new AtomicInteger();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.PENDING);
        ServicePluginHost host = new ServicePluginHost(gate, new MemoryPluginCallStore(), List.of(echo(hits)));
        PluginContext context = new PluginContext(null);
        PluginResult pending = join(host.invoke(pluginCall("exec-plugin-deny", "echo", PluginPhase.afterModel, "query", "", null), context));
        PluginResult denied = join(host.completePending(
                new PassApproval("aa", "exec-plugin-deny", pending.pass().passId(), PassDecision.DENY, temp.resolve("user")),
                context));
        assertEquals(0, hits.get());
        assertFalse(denied.executed());
        assertEquals(PassDecision.DENY, denied.pass().decision());
    }

    @Test
    void decisionsAreWrittenToPluginCall() {
        MemoryPluginCallStore store = new MemoryPluginCallStore();
        ServicePluginHost host = new ServicePluginHost(
                new ScriptedPassGate(PassDecision.DENY, PassDecision.PENDING),
                store,
                List.of(echo(new AtomicInteger())));
        join(host.invoke(pluginCall("exec-log", "echo", PluginPhase.beforeTool, "one", "", null), new PluginContext(null)));
        join(host.invoke(pluginCall("exec-log", "echo", PluginPhase.afterTool, "two", "", null), new PluginContext(null)));
        List<PluginCallEntry> entries = join(store.find("exec-log"));
        assertEquals(2, entries.size());
        assertEquals(PassDecision.DENY, entries.get(0).decision());
        assertFalse(entries.get(0).executed());
        assertEquals(PassDecision.PENDING, entries.get(1).decision());
        assertFalse(entries.get(1).executed());
    }

    private PluginCall pluginCall(String executionId, String pluginId, PluginPhase phase, String arguments, String modelOutput, PluginFailure failure) {
        return new PluginCall("aa", executionId, temp.resolve("user"), temp.resolve("workspace"), pluginId, phase, arguments, modelOutput, failure);
    }

    private static AgentPlugin echo(AtomicInteger hits) {
        return new AgentPlugin() {
            @Override
            public String id() {
                return "echo";
            }

            @Override
            public CompletionStage<String> invoke(PluginCall call, PluginContext context) {
                hits.incrementAndGet();
                return CompletableFuture.completedFuture("echoed");
            }
        };
    }

    private static AgentPlugin broken() {
        return new AgentPlugin() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public CompletionStage<String> invoke(PluginCall call, PluginContext context) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
        };
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

    private static final class RecordingGuard implements HallucinationGuard {
        private final List<String> seen = new ArrayList<>();

        @Override
        public CompletionStage<Void> check(String executionId, String modelOutput) {
            seen.add(executionId + "|" + modelOutput);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ScriptedPassGate implements PassGate {
        private final Queue<PassDecision> decisions = new ArrayDeque<>();
        private final Runnable beforeAcquire;
        private final List<PassRequest> requests = new CopyOnWriteArrayList<>();
        private final Map<String, Pass> passes = new ConcurrentHashMap<>();

        private ScriptedPassGate(PassDecision decision) {
            this(decision, () -> {
            });
        }

        private ScriptedPassGate(PassDecision first, PassDecision second) {
            this(first, () -> {
            });
            decisions.add(second);
        }

        private ScriptedPassGate(PassDecision decision, Runnable beforeAcquire) {
            this.beforeAcquire = beforeAcquire;
            decisions.add(decision);
        }

        @Override
        public CompletionStage<Pass> acquire(PassRequest request) {
            beforeAcquire.run();
            requests.add(request);
            PassDecision decision = decisions.size() == 1 ? decisions.peek() : decisions.remove();
            Pass pass = new Pass(
                    UUID.randomUUID().toString(),
                    request.userId(),
                    request.executionId(),
                    request.toolName(),
                    request.pluginId(),
                    request.argumentHash(),
                    decision,
                    reason(decision));
            passes.put(pass.passId(), pass);
            return CompletableFuture.completedFuture(pass);
        }

        @Override
        public CompletionStage<Pass> decide(PassApproval approval) {
            Pass existing = passes.get(approval.passId());
            if (existing == null || !existing.userId().equals(approval.userId()) || !existing.executionId().equals(approval.executionId())) {
                throw new AgentException(ErrorCode.AUTH, "pass");
            }
            Pass updated = new Pass(
                    existing.passId(),
                    existing.userId(),
                    existing.executionId(),
                    existing.toolName(),
                    existing.pluginId(),
                    existing.argumentHash(),
                    approval.decision(),
                    approval.decision() == PassDecision.ALLOW ? "approved" : "denied");
            passes.put(updated.passId(), updated);
            return CompletableFuture.completedFuture(updated);
        }

        @Override
        public CompletionStage<List<Pass>> pending(String userId, Path userDirectory) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<ExecutionStatus> executionStatus(String executionId) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static String reason(PassDecision decision) {
        return switch (decision) {
            case ALLOW -> "policy";
            case DENY -> "policy";
            case SKIPPED -> "agentpass-not-enabled";
            case PENDING -> "approval-required";
        };
    }
}
