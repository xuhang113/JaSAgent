package dev.jasagent.tool;

import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.CallStats;
import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 核对时钟不改调用统计、也不申请通行证，外部工具成功时统计只加一次。
 */
class ExternalUsageTest {
    @TempDir
    Path temp;

    /**
     * 调用时钟后，断言 clock 的成功次数没有增加。
     */
    @Test
    void clockLeavesSuccessUnchanged() {
        long before = CallStats.load().count("clock").success();
        join(clockCatalog().call(clockCall()));
        assertEquals(before, CallStats.load().count("clock").success());
    }

    /**
     * 调用时钟后，断言 clock 的失败次数没有增加。
     */
    @Test
    void clockLeavesFailureUnchanged() {
        long before = CallStats.load().count("clock").failure();
        join(clockCatalog().call(clockCall()));
        assertEquals(before, CallStats.load().count("clock").failure());
    }

    /**
     * 断言时钟调用不会向通行证门发出申请。
     */
    @Test
    void clockDoesNotAcquire() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        DefaultToolCatalog catalog = new DefaultToolCatalog(gate, new MemoryToolCallStore(), List.of(new ClockTool()));
        join(catalog.call(clockCall()));
        assertEquals(0, gate.acquisitions());
    }

    /**
     * 直接记录一次 clock 成功时，断言成功次数增加 1，用来对照工具目录本身不会替时钟计数。
     */
    @Test
    void explicitClockRecordIncrementsSuccess() {
        CallStats stats = CallStats.load();
        long before = stats.count("clock").success();
        stats.recordSuccess("clock");
        assertEquals(before + 1, stats.count("clock").success());
    }

    /**
     * 外部天气工具获准执行后，断言成功次数只增加 1。
     */
    @Test
    void weatherSuccessIncrementsOnce() {
        long before = CallStats.load().count("weather").success();
        Tool weather = new Tool() {
            /**
             * 返回名为 weather 的外部工具规格。
             *
             * @return 非内部的天气工具规格
             */
            @Override
            public ToolSpec spec() {
                return new ToolSpec("weather", "weather", false);
            }

            /**
             * 不读取参数，直接返回固定的晴天文本。
             *
             * @param call 本次工具调用
             * @return 立即完成、内容为晴天的阶段
             */
            @Override
            public CompletionStage<String> call(ToolCall call) {
                return CompletableFuture.completedFuture("sunny");
            }
        };
        DefaultToolCatalog catalog = new DefaultToolCatalog(new ScriptedPassGate(PassDecision.ALLOW), new MemoryToolCallStore(), List.of(weather));
        join(catalog.call(new ToolCall("aa", "exec-weather", temp.resolve("user"), temp.resolve("workspace"), "weather", Map.of("city", "shanghai"))));
        assertEquals(before + 1, CallStats.load().count("weather").success());
    }

    /**
     * 装一个只含时钟、通行证总是允许的工具目录。
     *
     * @return 用于时钟统计测试的目录
     */
    private DefaultToolCatalog clockCatalog() {
        return new DefaultToolCatalog(new ScriptedPassGate(PassDecision.ALLOW), new MemoryToolCallStore(), List.of(new ClockTool()));
    }

    /**
     * 做一次落在临时目录里的时钟调用。
     *
     * @return 工具名为 clock 的调用
     */
    private ToolCall clockCall() {
        return new ToolCall("aa", "exec-clock", temp.resolve("user"), temp.resolve("workspace"), "clock", Map.of());
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
     * 按固定决定立即发通行证，并记下申请次数。
     */
    private static final class ScriptedPassGate implements PassGate {
        private final List<PassRequest> requests = new CopyOnWriteArrayList<>();
        private final PassDecision decision;

        /**
         * 之后每次申请都返回这一种决定。
         *
         * @param decision 要返回的通行证决定
         */
        private ScriptedPassGate(PassDecision decision) {
            this.decision = decision;
        }

        /**
         * 返回已经收到的通行证申请数。
         *
         * @return 申请次数
         */
        private int acquisitions() {
            return requests.size();
        }

        /**
         * 记下申请并立即返回预设决定的通行证。
         *
         * @param request 工具目录发来的申请
         * @return 立即完成的通行证
         */
        @Override
        public CompletionStage<Pass> acquire(PassRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(new Pass(
                    UUID.randomUUID().toString(),
                    request.userId(),
                    request.executionId(),
                    request.toolName(),
                    request.pluginId(),
                    request.argumentHash(),
                    decision,
                    "policy"));
        }
    }
}
