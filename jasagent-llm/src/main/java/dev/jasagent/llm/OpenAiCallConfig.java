package dev.jasagent.llm;

import java.time.Duration;
import java.util.Map;

public record OpenAiCallConfig(
        String baseUrl,
        String apiKey,
        String modelName,
        Duration timeout,
        int maxRetries,
        boolean logRequests,
        boolean logResponses,
        Map<String, Object> customParameters,
        boolean returnThinking) {

    public static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    public static final String DEEPSEEK_MODEL = "deepseek-flash";

    public static OpenAiCallConfig deepSeek(LlmSettings settings) {
        boolean thinking = settings.deepseekThinking();
        Map<String, Object> custom = Map.of("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
        return new OpenAiCallConfig(
                DEEPSEEK_BASE_URL,
                emptyIfNull(settings.deepseekApiKey()),
                DEEPSEEK_MODEL,
                settings.timeout(),
                0,
                false,
                false,
                custom,
                thinking);
    }

    public static OpenAiCallConfig gatewayChat(LlmSettings settings) {
        return new OpenAiCallConfig(
                settings.gatewayBaseUrl(),
                emptyIfNull(settings.gatewayApiKey()),
                settings.gatewayModel(),
                settings.timeout(),
                0,
                false,
                false,
                Map.of(),
                false);
    }

    public static OpenAiCallConfig gatewayEmbed(LlmSettings settings) {
        return new OpenAiCallConfig(
                settings.gatewayBaseUrl(),
                emptyIfNull(settings.gatewayApiKey()),
                settings.gatewayEmbeddingModel(),
                settings.timeout(),
                0,
                false,
                false,
                Map.of(),
                false);
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
