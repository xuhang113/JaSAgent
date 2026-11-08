package dev.jasagent.api.llm;

public record LlmChatResponse(
        String provider,
        String model,
        String text,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        boolean fallback) {
}
