package dev.jasagent.persist;

public record ExecutionRow(
        String executionId,
        String userId,
        String sessionId,
        String workspacePath,
        String status,
        String otelTraceId,
        String otelRootSpanId) {
}
