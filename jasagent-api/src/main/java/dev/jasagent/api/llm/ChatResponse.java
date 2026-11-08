package dev.jasagent.api.llm;

public record ChatResponse(
        String provider,
        String model,
        ChatMessage message,
        TokenUsage usage,
        long latencyMillis,
        boolean fallback) {
}
