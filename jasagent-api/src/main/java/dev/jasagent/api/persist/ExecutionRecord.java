package dev.jasagent.api.persist;

public record ExecutionRecord(
        String executionId,
        String userId,
        String sessionId,
        String workspacePath,
        String status,
        String otelTraceId,
        String otelRootSpanId,
        String errorCode,
        String outputText) {
}
