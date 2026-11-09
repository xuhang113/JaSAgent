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

/**
 * 核对工具目录的重名失败、通行证决定、挂起后续执行、调用记录和模块边界。
 */
class ToolCatalogTest {
    @TempDir
    Path temp;

    /**
     * 登记两个同名时钟时，断言目录在构造时就以工具错误失败。
     */
    @Test
    void duplicateToolNameFailsStartup() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        AgentException error = assertThrows(AgentException.class, () -> new DefaultToolCatalog(
                gate,
                new MemoryToolCallStore(),
                List.of(new ClockTool(), new ClockTool())));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    /**
     * 通行证即使会拒绝，断言时钟仍返回接近当前时刻的 UTC 文本，并且没有申请通行证。
     */
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

    /**
     * 断言外部工具先拿到通行证，工具体才执行，且申请里带上用户、执行、目录和工具名。
     */
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

    /**
     * 断言按键排序后的参数文本，其 SHA-256 与通行证申请里的参数哈希一致。
     */
    @Test
    void argumentHashMatchesIndependentSha256() throws Exception {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        DefaultToolCatalog catalog = catalog(gate, new MemoryToolCallStore(), new AtomicInteger(), null, "weather");
        join(catalog.call(call("exec-hash", "weather", Map.of("b", "2", "a", "1"))));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest("a=1\nb=2".getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(hashed), gate.requests.get(0).argumentHash());
    }

    /**
     * 通行证拒绝时，断言工具体没有执行，结果里的决定是拒绝。
     */
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

    /**
     * 通行证允许时，断言工具体执行一次，并带回固定输出和允许决定。
     */
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

    /**
     * 通行证待批时，断言工具体没有执行，结果保持待批。
     */
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

    /**
     * 通行证被跳过时，断言工具仍然执行，原因是通行证功能未启用。
     */
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

    /**
     * 待批之后改成允许时，断言执行的是原来的执行编号和参数，并且只执行一次。
     */
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

    /**
     * 待批之后改成拒绝时，断言工具体仍然没有执行。
     */
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

    /**
     * 两次外部调用分别被拒绝和挂起时，断言调用记录按顺序记下工具名、决定和未执行。
     */
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

    /**
     * 两张通行证都停在门里时，断言两次外部调用都已进入申请，放开后两边都能结束。
     */
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

    /**
     * 读工具、插件和通行证模块的源码与构建文件，断言它们没有互相引用对方的包，也没有带上禁用的运行时或图框架。
     */
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

    /**
     * 用一个会计数的外部工具装目录。
     *
     * @param gate 测试用的通行证门
     * @param store 内存调用记录
     * @param hits 工具体执行次数
     * @param seen 用来记下实际执行编号；不需要时为 null
     * @param name 外部工具名
     * @return 只含这一个工具的目录
     */
    private DefaultToolCatalog catalog(PassGate gate, MemoryToolCallStore store, AtomicInteger hits, AtomicReference<String> seen, String name) {
        return new DefaultToolCatalog(gate, store, List.of(tool(name, hits, seen)));
    }

    /**
     * 做一个外部工具：执行时加一次计数，可选地记下执行编号，并返回带名字的固定输出。
     *
     * @param name 工具名
     * @param hits 执行次数计数器
     * @param seen 接收执行编号的引用；不需要时为 null
     * @return 非内部的测试工具
     */
    private Tool tool(String name, AtomicInteger hits, AtomicReference<String> seen) {
        return new Tool() {
            /**
             * 返回用构造时的名字做成的外部工具规格。
             *
             * @return 非内部的工具规格
             */
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, name, false);
            }

            /**
             * 把执行次数加一，记下执行编号，并返回带工具名的固定输出。
             *
             * @param call 本次工具调用
             * @return 立即完成的固定输出
             */
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

    /**
     * 做一次落在临时用户目录和工作区里的工具调用。
     *
     * @param executionId 执行编号
     * @param name 工具名
     * @param arguments 工具参数
     * @return 可交给目录的调用
     */
    private ToolCall call(String executionId, String name, Map<String, String> arguments) {
        return new ToolCall("aa", executionId, temp.resolve("user"), temp.resolve("workspace"), name, arguments);
    }

    /**
     * 把模块的构建文件和源码路径收进列表，供边界检查读取。
     *
     * @param module 模块目录
     * @param out 收集到的文件路径，方法会向其中追加
     */
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

    /**
     * 在 30 秒内等到阶段结束。运行时异常和错误原样抛出，中断会恢复中断标记。
     *
     * @param <T> 阶段结果的类型
     * @param stage 要等待的异步阶段
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

    /**
     * 按预先排好的决定依次发通行证，并记住申请和已发出的通行证。
     */
    private static final class ScriptedPassGate implements PassGate {
        private final Queue<PassDecision> decisions = new ArrayDeque<>();
        private final Runnable beforeAcquire;
        private final List<PassRequest> requests = new CopyOnWriteArrayList<>();
        private final Map<String, Pass> passes = new ConcurrentHashMap<>();

        /**
         * 每次申请都返回同一种决定。
         *
         * @param decision 要返回的通行证决定
         */
        private ScriptedPassGate(PassDecision decision) {
            this(decision, () -> {
            });
        }

        /**
         * 前两次申请按顺序返回两种决定，之后继续用第二种。
         *
         * @param first 第一次申请的决定
         * @param second 从第二次起使用的决定
         */
        private ScriptedPassGate(PassDecision first, PassDecision second) {
            this(first, () -> {
            });
            decisions.add(second);
        }

        /**
         * 返回一种决定，并在记录申请之前先跑给定动作。
         *
         * @param decision 要返回的通行证决定
         * @param beforeAcquire 发通行证前执行的动作
         */
        private ScriptedPassGate(PassDecision decision, Runnable beforeAcquire) {
            this.beforeAcquire = beforeAcquire;
            decisions.add(decision);
        }

        /**
         * 先跑预设动作，再记下申请。队列里只剩一种决定时反复使用它。
         *
         * @param request 工具目录发来的申请
         * @return 立即完成的通行证
         */
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

        /**
         * 把仍在待批的通行证改成批准或拒绝。通行证对不上用户、执行，或已经决定过时抛出业务异常。
         *
         * @param approval 用户对通行证的决定
         * @return 更新后的通行证
         */
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

        /**
         * 这个替身不提供待办列表。
         *
         * @param userId 用户编号，这里不使用
         * @param userDirectory 用户目录，这里不使用
         * @return 立即完成的空列表
         */
        @Override
        public CompletionStage<List<Pass>> pending(String userId, Path userDirectory) {
            return CompletableFuture.completedFuture(List.of());
        }

        /**
         * 这个替身不跟踪执行状态。
         *
         * @param executionId 执行编号，这里不使用
         * @return 立即完成的空结果
         */
        @Override
        public CompletionStage<ExecutionStatus> executionStatus(String executionId) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 把通行证申请停住，直到测试放开，用来观察两次外部调用能否重叠进入。
     */
    private static final class HoldGate implements PassGate {
        private final AtomicInteger entered = new AtomicInteger();
        private final CountDownLatch release = new CountDownLatch(1);

        /**
         * 把进入次数加一，然后等到放行信号再返回允许。超时未放开时失败。
         *
         * @param request 工具目录发来的申请
         * @return 放行后完成、决定为允许的通行证
         */
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

        /**
         * 这个重叠测试不使用事后决定，调用即失败。
         *
         * @param approval 不会被处理的决定
         * @return 不返回；调用直接抛出不支持操作
         */
        @Override
        public CompletionStage<Pass> decide(PassApproval approval) {
            throw new UnsupportedOperationException();
        }

        /**
         * 这个替身不提供待办列表。
         *
         * @param userId 用户编号，这里不使用
         * @param userDirectory 用户目录，这里不使用
         * @return 立即完成的空列表
         */
        @Override
        public CompletionStage<List<Pass>> pending(String userId, Path userDirectory) {
            return CompletableFuture.completedFuture(List.of());
        }

        /**
         * 这个替身不跟踪执行状态。
         *
         * @param executionId 执行编号，这里不使用
         * @return 立即完成的空结果
         */
        @Override
        public CompletionStage<ExecutionStatus> executionStatus(String executionId) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 按决定给出测试用的原因文本。
     *
     * @param decision 通行证决定
     * @return 写进通行证的原因
     */
    private static String reason(PassDecision decision) {
        return switch (decision) {
            case ALLOW -> "policy";
            case DENY -> "policy";
            case SKIPPED -> "agentpass-not-enabled";
            case PENDING -> "approval-required";
        };
    }
}
