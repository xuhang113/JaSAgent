package dev.jasagent.agentpass;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.PassGate;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

public final class PassSettings {
    public static final String ENABLED = "jasagent.agentpass.enabled";

    private PassSettings() {
    }

    public static boolean enabled(Map<String, String> configuration) {
        if (configuration == null || !configuration.containsKey(ENABLED)) {
            return true;
        }
        String value = configuration.get(ENABLED);
        if (value == null || value.isBlank() || "true".equals(value.trim())) {
            return true;
        }
        if ("false".equals(value.trim())) {
            return false;
        }
        throw new AgentException(ErrorCode.INTERNAL, ENABLED);
    }

    public static PassGate create(Map<String, String> configuration, Function<String, Path> userDirectories) {
        if (!enabled(configuration)) {
            return new DisabledPassGate();
        }
        return new PolicyPassGate(userDirectories);
    }
}
