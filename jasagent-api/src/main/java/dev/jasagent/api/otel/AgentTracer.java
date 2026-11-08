package dev.jasagent.api.otel;

import java.util.concurrent.CompletionStage;

public interface AgentTracer {
    CompletionStage<AgentSpan> startRoot(String executionId);

    CompletionStage<AgentSpan> startChild(String name, String executionId, String traceId, String parentSpanId);

    CompletionStage<Void> end(AgentSpan span);
}
