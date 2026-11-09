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

/**
 * 覆盖插件宿主的加载、通行证拦截、挂起后的审批和调用记录。
 * 接地插件只在模型输出之后检查文本，其它阶段不调用检查器。
 */
class PluginHostTest {
    @TempDir
    Path temp;

    /**
     * 确认服务加载器能发现标识为 grounding.default 的接地插件。
     * 副作用：只装宿主，不调用插件。
     */
    @Test
    void serviceLoaderLoadsGroundingDefault() {
        ServicePluginHost host = ServicePluginHost.load(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore());
        assertTrue(host.ids().contains("grounding.default"));
    }

    /**
     * 确认 afterModel 阶段会把执行标识和模型输出交给幻觉检查，并记为已执行。
     * 副作用：检查器记下 exec-g|model-text，宿主追加一条调用记录。
     */
    @Test
    void groundingDefaultRunsOnAfterModel() {
        RecordingGuard guard = new RecordingGuard();
        ServicePluginHost host = ServicePluginHost.load(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore());
        PluginResult result = join(host.invoke(pluginCall("exec-g", "grounding.default", PluginPhase.afterModel, "query", "model-text", null), new PluginContext(guard)));
        assertEquals(List.of("exec-g|model-text"), guard.seen);
        assertTrue(result.executed());
    }

    /**
     * 确认 beforeModel 阶段不调用幻觉检查，但插件仍算执行完成。
     * 副作用：宿主追加一条调用记录，检查器列表保持为空。
     */
    @Test
    void groundingDefaultSkipsGuardOnOtherPhase() {
        RecordingGuard guard = new RecordingGuard();
        ServicePluginHost host = ServicePluginHost.load(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore());
        PluginResult result = join(host.invoke(pluginCall("exec-before", "grounding.default", PluginPhase.beforeModel, "query", "model-text", null), new PluginContext(guard)));
        assertTrue(guard.seen.isEmpty());
        assertTrue(result.executed());
    }

    /**
     * 确认申请通行证时插件体还没执行，申请里带上用户、执行和插件标识。
     * 副作用：通过后把回声插件的计数加一，并追加调用记录。
     */
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

    /**
     * 确认参数哈希等于对阶段名、参数和模型输出按行独立计算的 SHA-256。
     * 副作用：执行一次回声插件并追加调用记录。
     */
    @Test
    void pluginArgumentHashMatchesIndependentSha256() throws Exception {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        ServicePluginHost host = new ServicePluginHost(gate, new MemoryPluginCallStore(), List.of(echo(new AtomicInteger())));
        join(host.invoke(pluginCall("exec-hash", "echo", PluginPhase.afterModel, "query", "model-text", null), new PluginContext(null)));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest("afterModel\nquery\nmodel-text".getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(hashed), gate.requests.get(0).argumentHash());
    }

    /**
     * 确认通行证被拒绝时不进入插件，结果里的决定仍是拒绝。
     * 副作用：追加一条未执行的调用记录。
     */
    @Test
    void denyDoesNotEnterPlugin() {
        AtomicInteger hits = new AtomicInteger();
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.DENY), new MemoryPluginCallStore(), List.of(echo(hits)));
        PluginResult result = join(host.invoke(pluginCall("exec-deny", "echo", PluginPhase.beforeTool, "query", "", null), new PluginContext(null)));
        assertEquals(0, hits.get());
        assertEquals(PassDecision.DENY, result.pass().decision());
        assertFalse(result.executed());
    }

    /**
     * 确认通行证待审批时不进入插件，结果里的决定仍是待审批。
     * 副作用：追加一条未执行的调用记录，并把调用挂起。
     */
    @Test
    void pendingDoesNotEnterPlugin() {
        AtomicInteger hits = new AtomicInteger();
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.PENDING), new MemoryPluginCallStore(), List.of(echo(hits)));
        PluginResult result = join(host.invoke(pluginCall("exec-pending", "echo", PluginPhase.beforeTool, "query", "", null), new PluginContext(null)));
        assertEquals(0, hits.get());
        assertEquals(PassDecision.PENDING, result.pass().decision());
        assertFalse(result.executed());
    }

    /**
     * 确认插件失败且没有要求中断时，调用返回失败结果而不是抛出。
     * 副作用：追加一条失败且未执行成功的调用记录。
     */
    @Test
    void pluginFailureContinuesByDefault() {
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore(), List.of(broken()));
        PluginResult result = join(host.invoke(pluginCall("exec-fail", "broken", PluginPhase.afterTool, "query", "", null), new PluginContext(null)));
        assertTrue(result.failed());
        assertFalse(result.executed());
    }

    /**
     * 确认失败策略为中断时，宿主在记下失败后抛出插件错误。
     * 副作用：追加失败记录，并把插件错误抛给调用方。
     */
    @Test
    void pluginFailureFailStopsCall() {
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore(), List.of(broken()));
        AgentException error = assertThrows(AgentException.class, () -> join(host.invoke(
                pluginCall("exec-stop", "broken", PluginPhase.afterTool, "query", "", PluginFailure.FAIL),
                new PluginContext(null))));
        assertEquals(ErrorCode.PLUGIN, error.code());
    }

    /**
     * 确认通行证被跳过时仍然执行插件，结果里保留跳过决定。
     * 副作用：回声插件计数加一，并追加调用记录。
     */
    @Test
    void skippedExecutesPlugin() {
        AtomicInteger hits = new AtomicInteger();
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.SKIPPED), new MemoryPluginCallStore(), List.of(echo(hits)));
        PluginResult result = join(host.invoke(pluginCall("exec-skip", "echo", PluginPhase.onNodeStart, "query", "", null), new PluginContext(null)));
        assertEquals(1, hits.get());
        assertEquals(PassDecision.SKIPPED, result.pass().decision());
    }

    /**
     * 确认两个标识都是 echo 的插件让宿主构造失败，错误码是插件错误。
     * 副作用：宿主不会登记成功。
     */
    @Test
    void duplicatePluginIdFailsLoad() {
        AgentException error = assertThrows(AgentException.class, () -> new ServicePluginHost(
                new ScriptedPassGate(PassDecision.ALLOW),
                new MemoryPluginCallStore(),
                List.of(echo(new AtomicInteger()), echo(new AtomicInteger()))));
        assertEquals(ErrorCode.PLUGIN, error.code());
    }

    /**
     * 确认待审批的调用在允许后才执行，而且执行标识仍是当初挂起的那次。
     * 副作用：先挂起再执行，回声插件计数变为 1，并写下两次调用记录。
     */
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

    /**
     * 确认待审批之后被拒绝的调用不会进入插件。
     * 副作用：写下未执行的拒绝记录，插件计数保持 0。
     */
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

    /**
     * 确认同一次执行上的拒绝和待审批会按调用顺序写进插件记录。
     * 副作用：向内存记录追加两条未执行的决定。
     */
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

    /**
     * 组装用户 aa、临时目录和给定阶段的插件调用。
     *
     * @param executionId 执行标识
     * @param pluginId 插件标识
     * @param phase 插件所处阶段
     * @param arguments 插件参数文本
     * @param modelOutput 模型输出，afterModel 阶段会交给检查器
     * @param failure 失败时是继续还是中断，空引用表示默认继续
     * @return 指向本次临时目录的插件调用
     */
    private PluginCall pluginCall(String executionId, String pluginId, PluginPhase phase, String arguments, String modelOutput, PluginFailure failure) {
        return new PluginCall("aa", executionId, temp.resolve("user"), temp.resolve("workspace"), pluginId, phase, arguments, modelOutput, failure);
    }

    /**
     * 做一个标识为 echo 的插件，每次执行都把计数器加一。
     *
     * @param hits 记录插件体实际执行次数的计数器
     * @return 返回 echoed 的插件
     */
    private static AgentPlugin echo(AtomicInteger hits) {
        /**
         * 标识固定为 echo 的计数插件。
         */
        return new AgentPlugin() {
            /**
             * 返回宿主用来登记的固定标识。
             *
             * @return 固定标识 echo
             */
            @Override
            public String id() {
                return "echo";
            }

            /**
             * 把执行次数加一后返回 echoed。
             * 副作用：计数器加一。
             *
             * @param call 插件调用，这里不读取内容
             * @param context 插件上下文，这里不使用
             * @return 已完成的文本 echoed
             */
            @Override
            public CompletionStage<String> invoke(PluginCall call, PluginContext context) {
                hits.incrementAndGet();
                return CompletableFuture.completedFuture("echoed");
            }
        };
    }

    /**
     * 做一个标识为 broken 的插件，调用立即失败。
     *
     * @return 以 boom 失败的插件
     */
    private static AgentPlugin broken() {
        /**
         * 标识固定为 broken、调用必定失败的插件。
         */
        return new AgentPlugin() {
            /**
             * 返回宿主用来登记的固定标识。
             *
             * @return 固定标识 broken
             */
            @Override
            public String id() {
                return "broken";
            }

            /**
             * 让插件调用以非法状态异常结束，消息是 boom。
             *
             * @param call 插件调用，这里不使用
             * @param context 插件上下文，这里不使用
             * @return 已经失败的阶段
             */
            @Override
            public CompletionStage<String> invoke(PluginCall call, PluginContext context) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
        };
    }

    /**
     * 在 30 秒内取回异步结果。运行时异常和错误原样抛出，其它原因包成非法状态。
     * 副作用：等待被中断时重新置上当前线程的中断标记。
     *
     * @param <T> 阶段的结果类型
     * @param stage 要等待的异步阶段
     * @return 阶段的正常结果
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
     * 记下每次幻觉检查收到的执行标识和模型输出。
     * 测试用它确认接地插件是否真的调用了检查。
     */
    private static final class RecordingGuard implements HallucinationGuard {
        private final List<String> seen = new ArrayList<>();

        /**
         * 把执行标识和模型输出按竖线拼起来记下。
         * 副作用：向 seen 追加一条记录。
         *
         * @param executionId 这次执行的标识
         * @param modelOutput 要检查的模型输出
         * @return 已经完成的空结果
         */
        @Override
        public CompletionStage<Void> check(String executionId, String modelOutput) {
            seen.add(executionId + "|" + modelOutput);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 按队列给出通行证决定的测试门。
     * 队列里还剩多个决定时逐个取走，只剩一个时重复使用它。
     */
    private static final class ScriptedPassGate implements PassGate {
        private final Queue<PassDecision> decisions = new ArrayDeque<>();
        private final Runnable beforeAcquire;
        private final List<PassRequest> requests = new CopyOnWriteArrayList<>();
        private final Map<String, Pass> passes = new ConcurrentHashMap<>();

        /**
         * 每次申请都返回同一种决定，申请前不额外执行动作。
         *
         * @param decision 要重复使用的决定
         */
        private ScriptedPassGate(PassDecision decision) {
            this(decision, () -> {
            });
        }

        /**
         * 先排上第一个决定，再排上第二个，用来按调用顺序给出不同决定。
         *
         * @param first 第一次申请返回的决定
         * @param second 第二次及之后使用的决定
         */
        private ScriptedPassGate(PassDecision first, PassDecision second) {
            this(first, () -> {
            });
            decisions.add(second);
        }

        /**
         * 记住决定，并在登记申请之前运行给定动作。
         *
         * @param decision 要排进队列的决定
         * @param beforeAcquire 申请被记下之前要跑的动作，用来观察插件体是否已经执行
         */
        private ScriptedPassGate(PassDecision decision, Runnable beforeAcquire) {
            this.beforeAcquire = beforeAcquire;
            decisions.add(decision);
        }

        /**
         * 先跑前置动作，再按队列取出决定并保存通行证。
         * 副作用：把申请追加到列表，并把通行证按编号放进映射，供之后的审批查找。
         *
         * @param request 插件带来的申请
         * @return 已完成的通行证，原因由决定种类生成
         */
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

        /**
         * 按审批改写已经发出的通行证。
         * 用户或执行标识对不上时以认证错误失败。允许的原因写成 approved，其它决定写成 denied。
         * 副作用：用同一张通行证编号覆盖映射里的旧决定。
         *
         * @param approval 用户对某张通行证的允许或拒绝
         * @return 更新决定后的通行证
         */
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

        /**
         * 这个测试门不提供待审批列表。
         *
         * @param userId 用户标识，这里不筛选
         * @param userDirectory 用户目录，这里不筛选
         * @return 已经完成的空列表
         */
        @Override
        public CompletionStage<List<Pass>> pending(String userId, Path userDirectory) {
            return CompletableFuture.completedFuture(List.of());
        }

        /**
         * 这个测试门不查询执行状态。
         *
         * @param executionId 执行标识，这里不使用
         * @return 已经完成、结果为空的阶段
         */
        @Override
        public CompletionStage<ExecutionStatus> executionStatus(String executionId) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 把通行证决定写成测试里约定的原因文本。
     * 允许和拒绝都是策略，跳过是未启用，待审批是需要批准。
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
