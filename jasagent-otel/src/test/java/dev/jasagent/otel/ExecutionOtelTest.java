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

class ExecutionOtelTest {
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

    @Test
    void defaultDoesNotExportOtlp() {
        try (ExecutionTelemetry telemetry = ExecutionTelemetry.open(Map.of())) {
            assertFalse(telemetry.otlpExportEnabled());
            assertEquals("", telemetry.exporterClassName());
        }
    }

    @Test
    void endpointEnablesOtlpExporter() {
        try (ExecutionTelemetry telemetry = ExecutionTelemetry.open(Map.of(
                "jasagent.otel.endpoint", "http://127.0.0.1:9/v1/traces"))) {
            assertTrue(telemetry.otlpExportEnabled());
            assertEquals("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter", telemetry.exporterClassName());
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
}
