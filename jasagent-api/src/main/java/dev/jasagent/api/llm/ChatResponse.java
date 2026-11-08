package dev.jasagent.api.llm;

public record ChatResponse(LlmProvider provider, String model, ChatMessage message, TokenUsage usage, long latencyMillis, boolean fallback) {
}
