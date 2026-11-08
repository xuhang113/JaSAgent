package dev.jasagent.api.exec;

public record ExecutionSnapshot(
        ExecutionId executionId,
        String userId,
        String sessionId,
        String workspace,
        ExecutionStatus status,
        String traceId,
        String rootSpanId,
        String output,
        String errorCode) {
}
