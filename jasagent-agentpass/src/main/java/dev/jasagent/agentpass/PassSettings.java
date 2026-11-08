package dev.jasagent.agentpass;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.Map;

public final class PassSettings {
    public static final String ENABLED = "jasagent.agentpass.enabled";

    private PassSettings() {
    }

    public static boolean enabled(Map<String, String> configuration) {
        if (configuration == null || !configuration.containsKey(ENABLED)) {
            return true;
        }
        String value = configuration.get(ENABLED);
        if (value == null || value.isBlank() || "true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new AgentException(ErrorCode.INTERNAL, ENABLED);
    }
}
