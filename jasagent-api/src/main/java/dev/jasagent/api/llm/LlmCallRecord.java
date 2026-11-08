package dev.jasagent.api.llm;

public record LlmCallRecord(
        String executionId,
        String provider,
        String model,
        long latencyMillis,
        int promptTokens,
        int completionTokens,
        String task) {
}
