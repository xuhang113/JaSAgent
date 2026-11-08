package dev.jasagent.api.llm;

public record LlmChatResponse(
        String provider,
        String model,
        String text,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        boolean fallback,
        String thinking) {

    public LlmChatResponse {
        if (thinking == null) {
            thinking = "";
        }
    }

    public LlmChatResponse(
            String provider,
            String model,
            String text,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            boolean fallback) {
        this(provider, model, text, promptTokens, completionTokens, totalTokens, fallback, "");
    }
}
