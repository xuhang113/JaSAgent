package dev.jasagent.llm;

import java.time.Duration;
import java.util.Map;

public record LlmSettings(
        String deepseekApiKey,
        String gatewayApiKey,
        String gatewayBaseUrl,
        String gatewayModel,
        boolean deepseekThinking,
        Duration timeout) {

    public static LlmSettings from(Map<String, String> env, Map<String, String> properties) {
        Map<String, String> safeEnv = env == null ? Map.of() : env;
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        String thinking = safeProperties.get("jasagent.deepseek.thinking");
        String timeoutRaw = safeProperties.get("jasagent.llm.timeout-seconds");
        long seconds = 60;
        if (timeoutRaw != null && !timeoutRaw.isBlank()) {
            seconds = Long.parseLong(timeoutRaw.trim());
        }
        return new LlmSettings(
                secret(safeEnv.get("JASAGENT_DEEPSEEK_API_KEY")),
                secret(safeEnv.get("JASAGENT_GATEWAY_API_KEY")),
                secret(safeEnv.get("JASAGENT_GATEWAY_BASE_URL")),
                secret(safeEnv.get("JASAGENT_GATEWAY_MODEL")),
                "true".equalsIgnoreCase(thinking),
                Duration.ofSeconds(seconds));
    }

    public boolean gatewayConfigured() {
        return gatewayBaseUrl != null && !gatewayBaseUrl.isBlank();
    }

    private static String secret(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
