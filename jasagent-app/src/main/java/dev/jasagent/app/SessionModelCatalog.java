package dev.jasagent.app;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.llm.LlmSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class SessionModelCatalog {
    static final String DEFAULT_MODEL = "deepseek-flash";
    static final String DEEPSEEK = "deepseek";
    static final String GATEWAY = "ai-gateway";

    private final LlmSettings settings;
    private final DeepSeekModels deepseek;
    private final GatewayProbe gateway;

    SessionModelCatalog(LlmSettings settings, DeepSeekModels deepseek, GatewayProbe gateway) {
        this.settings = settings;
        this.deepseek = deepseek;
        this.gateway = gateway;
    }

    static SessionModelCatalog fromEnvironment() {
        return new SessionModelCatalog(LlmSettings.fromEnvironment(), new DeepSeekModelDirectory(), new GatewayReachability());
    }

    List<ModelChoice> available() {
        List<ModelChoice> options = new ArrayList<>();
        if (present(settings.deepseekApiKey())) {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            ids.add(DEFAULT_MODEL);
            List<String> listed = deepseek.ids(settings.deepseekApiKey());
            if (listed != null) {
                for (String id : listed) {
                    if (id != null && !id.isBlank()) {
                        ids.add(id.trim());
                    }
                }
            }
            for (String id : ids) {
                options.add(new ModelChoice(id, DEEPSEEK, id));
            }
        }
        if (gatewayConfigured() && gateway.reachable(settings.gatewayBaseUrl())) {
            String id = settings.gatewayModel().trim();
            boolean collide = false;
            for (ModelChoice option : options) {
                if (option.id().equals(id)) {
                    collide = true;
                    break;
                }
            }
            options.add(new ModelChoice(id, GATEWAY, collide ? id + "（Gateway）" : id));
        }
        return List.copyOf(options);
    }

    ModelChoice require(String model, String provider) {
        String id = model == null ? "" : model.trim();
        String kind = provider == null ? "" : provider.trim();
        List<ModelChoice> options = available();
        if (kind.isEmpty()) {
            ModelChoice found = null;
            for (ModelChoice option : options) {
                if (option.id().equals(id)) {
                    if (found != null) {
                        throw unavailable();
                    }
                    found = option;
                }
            }
            if (found == null) {
                throw unavailable();
            }
            return found;
        }
        for (ModelChoice option : options) {
            if (option.id().equals(id) && option.provider().equals(kind)) {
                return option;
            }
        }
        throw unavailable();
    }

    static String model(Map<String, Object> stored) {
        return text(stored.get("model"), DEFAULT_MODEL);
    }

    static String provider(Map<String, Object> stored) {
        return text(stored.get("modelProvider"), DEEPSEEK);
    }

    private boolean gatewayConfigured() {
        return present(settings.gatewayBaseUrl()) && present(settings.gatewayApiKey()) && present(settings.gatewayModel());
    }

    private static String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String cleaned = String.valueOf(value).trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static AgentException unavailable() {
        return new AgentException(ErrorCode.WORKFLOW, "模型不可用");
    }

    record ModelChoice(String id, String provider, String label) {
    }

    interface DeepSeekModels {
        List<String> ids(String apiKey);
    }

    interface GatewayProbe {
        boolean reachable(String baseUrl);
    }
}
