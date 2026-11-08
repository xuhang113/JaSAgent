package dev.jasagent.app;

import dev.jasagent.api.otel.AgentSpan;
import dev.jasagent.api.otel.AgentTracer;
import dev.jasagent.api.otel.SpanName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class SavedTraceTracer implements AgentTracer {
    private final Map<String, String> traceIds = new ConcurrentHashMap<>();
    private final Map<String, String> rootSpans = new ConcurrentHashMap<>();
    private final List<String> childTraceIds = new CopyOnWriteArrayList<>();

    void assign(String executionId, String traceId, String rootSpanId) {
        traceIds.put(executionId, traceId);
        rootSpans.put(executionId, rootSpanId);
    }

    List<String> childTraceIds() {
        return List.copyOf(childTraceIds);
    }

    @Override
    public CompletionStage<AgentSpan> startRoot(String executionId) {
        String traceId = traceIds.computeIfAbsent(executionId, ignored -> hex(32));
        String spanId = rootSpans.computeIfAbsent(executionId, ignored -> hex(16));
        return CompletableFuture.completedFuture(new FixedSpan(SpanName.EXECUTION, traceId, spanId, executionId));
    }

    @Override
    public CompletionStage<AgentSpan> startChild(String name, String executionId, String traceId, String parentSpanId) {
        childTraceIds.add(traceId == null ? "" : traceId);
        return CompletableFuture.completedFuture(new FixedSpan(name, traceId == null ? "" : traceId, hex(16), executionId));
    }

    @Override
    public CompletionStage<Void> end(AgentSpan span) {
        return CompletableFuture.completedFuture(null);
    }

    private static String hex(int length) {
        String raw = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        return raw.substring(0, length);
    }

    private record FixedSpan(String name, String traceId, String spanId, String executionId) implements AgentSpan {
    }
}
