package dev.jasagent.api.llm;

import java.util.List;

public record LlmChatResponse(
        String provider,
        String model,
        String text,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        boolean fallback,
        String thinking,
        List<LlmToolCall> toolCalls) {

    public LlmChatResponse {
        if (thinking == null) {
            thinking = "";
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public LlmChatResponse(
            String provider,
            String model,
            String text,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            boolean fallback,
            String thinking) {
        this(provider, model, text, promptTokens, completionTokens, totalTokens, fallback, thinking, List.of());
    }

    public LlmChatResponse(
            String provider,
            String model,
            String text,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            boolean fallback) {
        this(provider, model, text, promptTokens, completionTokens, totalTokens, fallback, "", List.of());
    }
}
