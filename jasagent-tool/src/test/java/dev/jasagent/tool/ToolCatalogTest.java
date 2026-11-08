package dev.jasagent.tool;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.execution.ExecutionStatus;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassApproval;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolCallEntry;
import dev.jasagent.api.tool.ToolResult;
import dev.jasagent.api.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCatalogTest {
    @TempDir
    Path temp;

    @Test
    void duplicateToolNameFailsStartup() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        AgentException error = assertThrows(AgentException.class, () -> new DefaultToolCatalog(
                gate,
                new MemoryToolCallStore(),
                List.of(new ClockTool(), new ClockTool())));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    @Test
    void clockReturnsUtcWithoutPass() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.DENY);
        DefaultToolCatalog catalog = new DefaultToolCatalog(gate, new MemoryToolCallStore(), List.of(new ClockTool()));
        ToolResult result = join(catalog.call(call("exec-clock", "clock", Map.of())));
        Instant parsed = Instant.parse(result.output());
        assertTrue(Duration.between(parsed, Instant.now()).abs().toSeconds() < 5);
        assertNull(result.pass());
        assertEquals(0, gate.requests.size());
        assertTrue(result.executed());
    }

    @Test
    void externalToolAcquiresPassBeforeBody() {
        AtomicInteger hits = new AtomicInteger();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW, () -> assertEquals(0, hits.get()));
        DefaultToolCatalog catalog = catalog(gate, new MemoryToolCallStore(), hits, null, "weather");
        Path user = temp.resolve("user");
        Path workspace = temp.resolve("workspace");
        ToolResult result = join(catalog.call(new ToolCall("aa", "exec-ext", user, workspace, "weather", Map.of("city", "shanghai"))));
        assertEquals(1, gate.requests.size());
        PassRequest request = gate.requests.get(0);
        assertEquals("aa", request.userId());
        assertEquals("exec-ext", request.executionId());
        assertEquals("weather", request.toolName());
        assertEquals(user, request.userDirectory());
        assertEquals(workspace, request.workspaceDirectory());
        assertEquals(1, hits.get());
        assertTrue(result.executed());
    }

    @Test
    void argumentHashMatchesIndependentSha256() throws Exception {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        DefaultToolCatalog catalog = catalog(gate, new MemoryToolCallStore(), new AtomicInteger(), null, "weather");
        join(catalog.call(call("exec-hash", "weather", Map.of("b", "2", "a", "1"))));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest("a=1\nb=2".getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(hashed), gate.requests.get(0).argumentHash());
    }

    @Test
    void denyDoesNotExecuteTool() {
        AtomicInteger hits = new AtomicInteger();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.DENY);
        DefaultToolCatalog catalog = catalog(gate, new MemoryToolCallStore(), hits, null, "weather");
        ToolResult result = join(catalog.call(call("exec-deny", "weather", Map.of())));
        assertEquals(0, hits.get());
        assertFalse(result.executed());
        assertEquals(PassDecision.DENY, result.pass().decision());
    }

    @Test
    void allowExecutesTool() {
        AtomicInteger hits = new AtomicInteger();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        DefaultToolCatalog catalog = catalog(gate, new MemoryToolCallStore(), hits, null, "weather");
        ToolResult result = join(catalog.call(call("exec-allow", "weather", Map.of())));
        assertEquals(1, hits.get());
        assertEquals("out-weather", result.output());
        assertEquals(PassDecision.ALLOW, result.pass().decision());
    }

    @Test
    void pendingDoesNotExecuteTool() {
        AtomicInteger hits = new AtomicInteger();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.PENDING);
        DefaultToolCatalog catalog = catalog(gate, new MemoryToolCallStore(), hits, null, "weather");
        ToolResult result = join(catalog.call(call("exec-pending", "weather", Map.of())));
        assertEquals(0, hits.get());
        assertFalse(result.executed());
        assertEquals(PassDecision.PENDING, result.pass().decision());
    }

    @Test
    void skippedExecutesTool() {
        AtomicInteger hits = new AtomicInteger();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.SKIPPED);
        DefaultToolCatalog catalog = catalog(gate, new MemoryToolCallStore(), hits, null, "weather");
        ToolResult result = join(catalog.call(call("exec-skip", "weather", Map.of())));
        assertEquals(1, hits.get());
        assertEquals(PassDecision.SKIPPED, result.pass().decision());
        assertEquals("agentpass-not-enabled", result.pass().reason());
    }

    @Test
    void allowPendingExecutesOriginalExecution() {
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> seen = new AtomicReference<>();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.PENDING);
        DefaultToolCatalog catalog = catalog(gate, new MemoryToolCallStore(), hits, seen, "weather");
        ToolResult pending = join(catalog.call(call("exec-tool", "weather", Map.of("city", "shanghai"))));
        assertEquals(0, hits.get());
        ToolResult allowed = join(catalog.completePending(new PassApproval(
                "aa", "exec-tool", pending.pass().passId(), PassDecision.ALLOW, temp.resolve("user"))));
        assertEquals(1, hits.get());
        assertEquals("exec-tool", allowed.executionId());
        assertEquals("exec-tool", seen.get());
        assertTrue(allowed.executed());
        assertEquals(PassDecision.ALLOW, allowed.pass().decision());
    }

    @Test
    void denyPendingDoesNotExecute() {
        AtomicInteger hits = new AtomicInteger();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.PENDING);
        DefaultToolCatalog catalog = catalog(gate, new MemoryToolCallStore(), hits, null, "weather");
        ToolResult pending = join(catalog.call(call("exec-tool-deny", "weather", Map.of())));
        ToolResult denied = join(catalog.completePending(new PassApproval(
                "aa", "exec-tool-deny", pending.pass().passId(), PassDecision.DENY, temp.resolve("user"))));
        assertEquals(0, hits.get());
        assertFalse(denied.executed());
        assertEquals(PassDecision.DENY, denied.pass().decision());
    }

    @Test
    void decisionsAreWrittenToToolCall() {
        MemoryToolCallStore store = new MemoryToolCallStore();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.DENY, PassDecision.PENDING);
        AtomicInteger hits = new AtomicInteger();
        DefaultToolCatalog catalog = new DefaultToolCatalog(gate, store, List.of(
                tool("weather", hits, null),
                tool("shell", hits, null)));
        join(catalog.call(call("exec-log", "weather", Map.of())));
        join(catalog.call(call("exec-log", "shell", Map.of())));
        List<ToolCallEntry> entries = join(store.find("exec-log"));
        assertEquals(2, entries.size());
        assertEquals(PassDecision.DENY, entries.get(0).decision());
        assertFalse(entries.get(0).executed());
        assertEquals("weather", entries.get(0).toolName());
        assertEquals(PassDecision.PENDING, entries.get(1).decision());
        assertFalse(entries.get(1).executed());
        assertEquals("shell", entries.get(1).toolName());
    }

    @Test
    @Timeout(10)
    void externalCallsCanOverlap() throws Exception {
        HoldGate gate = new HoldGate();
        DefaultToolCatalog catalog = new DefaultToolCatalog(gate, new MemoryToolCallStore(), List.of(
                tool("weather", new AtomicInteger(), null),
                tool("shell", new AtomicInteger(), null)));
        CompletionStage<ToolResult> first = catalog.call(call("exec-a", "weather", Map.of()));
        CompletionStage<ToolResult> second = catalog.call(call("exec-b", "shell", Map.of()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (gate.entered.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(2, gate.entered.get());
        gate.release.countDown();
        join(first);
        join(second);
    }

    @Test
    void modulesKeepPassAndGraphBoundaries() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")));
        List<Path> toolAndPlugin = new ArrayList<>();
        List<Path> agentpass = new ArrayList<>();
        collect(root.resolve("jasagent-tool"), toolAndPlugin);
        collect(root.resolve("jasagent-plugin"), toolAndPlugin);
        collect(root.resolve("jasagent-agentpass"), agentpass);
        List<Path> all = new ArrayList<>();
        all.addAll(toolAndPlugin);
        all.addAll(agentpass);
        String blockedRuntime = "spr" + "ingframework";
        String blockedModel = "spr" + "ing-ai";
        String blockedApp = "spr" + "ing-boot";
        String blockedGraph = "lang" + "graph";
        for (Path file : all) {
            String text = Files.readString(file).toLowerCase(Locale.ROOT);
            assertFalse(text.contains(blockedRuntime), file.toString());
            assertFalse(text.contains(blockedModel), file.toString());
            assertFalse(text.contains(blockedApp), file.toString());
            assertFalse(text.contains(blockedGraph), file.toString());
        }
        String agentpassPackage = "dev.jasagent.agent" + "pass";
        String toolPackage = "dev.jasagent.to" + "ol";
        String pluginPackage = "dev.jasagent.plug" + "in";
        for (Path file : toolAndPlugin) {
            assertFalse(Files.readString(file).contains(agentpassPackage), file.toString());
        }
        for (Path file : agentpass) {
            String text = Files.readString(file);
            assertFalse(text.contains(toolPackage), file.toString());
            assertFalse(text.contains(pluginPackage), file.toString());
        }
    }

    private DefaultToolCatalog catalog(PassGate gate, MemoryToolCallStore store, AtomicInteger hits, AtomicReference<String> seen, String name) {
        return new DefaultToolCatalog(gate, store, List.of(tool(name, hits, seen)));
    }

    private Tool tool(String name, AtomicInteger hits, AtomicReference<String> seen) {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, name, false);
            }

            @Override
            public CompletionStage<String> call(ToolCall call) {
                hits.incrementAndGet();
                if (seen != null) {
                    seen.set(call.executionId());
                }
                return CompletableFuture.completedFuture("out-" + name);
            }
        };
    }

    private ToolCall call(String executionId, String name, Map<String, String> arguments) {
        return new ToolCall("aa", executionId, temp.resolve("user"), temp.resolve("workspace"), name, arguments);
    }

    private static void collect(Path module, List<Path> out) throws Exception {
        Path pom = module.resolve("pom.xml");
        if (Files.exists(pom)) {
            out.add(pom);
        }
        Path src = module.resolve("src");
        if (Files.notExists(src)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(src)) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(out::add);
        }
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
                    java.util.UUID.randomUUID().toString(),
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
            if (existing.decision() != PassDecision.PENDING) {
                throw new AgentException(ErrorCode.INTERNAL, "pass already decided");
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

    private static final class HoldGate implements PassGate {
        private final AtomicInteger entered = new AtomicInteger();
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public CompletionStage<Pass> acquire(PassRequest request) {
            entered.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("not released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return new Pass(
                        java.util.UUID.randomUUID().toString(),
                        request.userId(),
                        request.executionId(),
                        request.toolName(),
                        request.pluginId(),
                        request.argumentHash(),
                        PassDecision.ALLOW,
                        "policy");
            });
        }

        @Override
        public CompletionStage<Pass> decide(PassApproval approval) {
            throw new UnsupportedOperationException();
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
