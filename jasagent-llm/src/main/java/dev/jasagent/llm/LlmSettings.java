package dev.jasagent.llm;

import java.time.Duration;
import java.util.Map;

public record LlmSettings(
        String deepseekApiKey,
        boolean deepseekThinking,
        String gatewayBaseUrl,
        String gatewayApiKey,
        String gatewayModel,
        boolean gatewayEmbeddings,
        String gatewayEmbeddingModel,
        Duration timeout) {

    public LlmSettings {
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
    }

    public static LlmSettings fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static LlmSettings fromEnvironment(Map<String, String> env) {
        return new LlmSettings(
                env.get("JASAGENT_DEEPSEEK_API_KEY"),
                truthy(env.get("JASAGENT_DEEPSEEK_THINKING")),
                env.get("JASAGENT_GATEWAY_BASE_URL"),
                env.get("JASAGENT_GATEWAY_API_KEY"),
                env.get("JASAGENT_GATEWAY_MODEL"),
                truthy(env.get("JASAGENT_GATEWAY_EMBEDDINGS")),
                env.get("JASAGENT_GATEWAY_EMBEDDING_MODEL"),
                timeout(env.get("JASAGENT_LLM_TIMEOUT_SECONDS")));
    }

    public boolean gatewayConfigured() {
        return !isBlank(gatewayBaseUrl);
    }

    private static boolean truthy(String value) {
        return value != null && value.equalsIgnoreCase("true");
    }

    private static Duration timeout(String seconds) {
        if (isBlank(seconds)) {
            return Duration.ofSeconds(60);
        }
        return Duration.ofSeconds(Long.parseLong(seconds.trim()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
