package dev.jasagent.api.llm;

public record ChatResponse(
        String provider,
        String model,
        ChatMessage message,
        TokenUsage usage,
        long latencyMillis,
        boolean fallback) {
    public ChatResponse(LlmProvider provider, String model, ChatMessage message, TokenUsage usage, long latencyMillis, boolean fallback) {
        this(provider == null ? null : provider.name(), model, message, usage, latencyMillis, fallback);
    }

    public LlmProvider providerKind() {
        if (provider == null) {
            return null;
        }
        try {
            return LlmProvider.valueOf(provider);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
