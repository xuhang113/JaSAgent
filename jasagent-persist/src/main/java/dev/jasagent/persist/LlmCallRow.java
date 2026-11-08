package dev.jasagent.persist;

public record LlmCallRow(
        String callId,
        String executionId,
        String provider,
        String modelName,
        long latencyMs,
        int promptTokens,
        int completionTokens,
        int totalTokens) {
}
