package dev.jasagent.otel;

import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对执行根跨度、子跨度共用链路，以及默认不导出、配置端点后才启用 OTLP。
 */
class ExecutionOtelTest {
    /**
     * 一次执行下应当出现的子跨度名称，覆盖调度、节点、模型、工具、插件、检索和评估。
     */
    private static final List<String> CHILD_SPANS = List.of(
            "agent.schedule",
            "workflow.node",
            "llm.chat",
            "tool.call",
            "plugin.invoke",
            "mcp.call",
            "rag.retrieve",
            "rag.rerank",
            "eval.run");

    /**
     * 根跨度必须使用执行跨度名称，并把执行标识写进属性。断言失败则本测试失败。
     */
    @Test
    void rootSpanNameAndAttribute() {
        String executionId = UUID.randomUUID().toString();
        try (ExecutionTelemetry telemetry = ExecutionTelemetry.open(Map.of())) {
            telemetry.startRoot(executionId);
            ObservedSpan root = telemetry.spans(executionId).get(0);
            assertEquals("jasagent.execution", root.name());
            assertEquals(executionId, root.attributes().get("jasagent.execution_id"));
        }
    }

    /**
     * 列出的每个子跨度都要挂在同一条链路和同一个根跨度下，并带上执行标识。断言失败则本测试失败。
     */
    @Test
    void childSpansShareSavedTraceId() {
        String executionId = UUID.randomUUID().toString();
        try (ExecutionTelemetry telemetry = ExecutionTelemetry.open(Map.of())) {
            telemetry.startRoot(executionId);
            for (String name : CHILD_SPANS) {
                telemetry.startChild(executionId, name);
            }
            List<ObservedSpan> spans = telemetry.spans(executionId);
            assertEquals(1 + CHILD_SPANS.size(), spans.size());
            String traceId = telemetry.traceId(executionId);
            String rootSpanId = telemetry.rootSpanId(executionId);
            assertFalse(traceId.isBlank());
            assertFalse(rootSpanId.isBlank());
            assertEquals("jasagent.execution", spans.get(0).name());
            assertEquals(traceId, spans.get(0).traceId());
            assertEquals(rootSpanId, spans.get(0).spanId());
            for (int index = 0; index < CHILD_SPANS.size(); index++) {
                ObservedSpan child = spans.get(index + 1);
                assertEquals(CHILD_SPANS.get(index), child.name());
                assertEquals(traceId, child.traceId());
                assertEquals(rootSpanId, child.parentSpanId());
                assertEquals(executionId, child.attributes().get("jasagent.execution_id"));
            }
        }
    }

    /**
     * 跨进虚拟线程后仍能读到这条执行的链路标识，调用方线程本身不留下当前跨度。断言失败则本测试失败。
     */
    @Test
    void asyncHandoffKeepsTraceId() {
        String executionId = UUID.randomUUID().toString();
        try (ExecutionTelemetry telemetry = ExecutionTelemetry.open(Map.of())) {
            telemetry.startRoot(executionId);
            AtomicBoolean virtual = new AtomicBoolean(false);
            String seen = join(telemetry.propagate(executionId, () -> {
                virtual.set(Thread.currentThread().isVirtual());
                return Span.current().getSpanContext().getTraceId();
            }));
            assertTrue(virtual.get());
            assertEquals(telemetry.traceId(executionId), seen);
            assertFalse(Span.current().getSpanContext().isValid());
        }
    }

    /**
     * 不给端点时不得创建导出器。断言失败则本测试失败。
     */
    @Test
    void defaultDoesNotExportOtlp() {
        try (ExecutionTelemetry telemetry = ExecutionTelemetry.open(Map.of())) {
            assertFalse(telemetry.otlpExportEnabled());
            assertEquals("", telemetry.exporterClassName());
        }
    }

    /**
     * 给出端点后必须启用 HTTP 上的 OTLP 跨度导出器。断言失败则本测试失败。
     */
    @Test
    void endpointEnablesOtlpExporter() {
        try (ExecutionTelemetry telemetry = ExecutionTelemetry.open(Map.of(
                "jasagent.otel.endpoint", "http://127.0.0.1:9/v1/traces"))) {
            assertTrue(telemetry.otlpExportEnabled());
            assertEquals("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter", telemetry.exporterClassName());
        }
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
