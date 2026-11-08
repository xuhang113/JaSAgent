package dev.jasagent.api.llm;

public record LlmCall(
        String executionId,
        String task,
        String provider,
        String model,
        long latencyMillis,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        boolean fallback) {
}
