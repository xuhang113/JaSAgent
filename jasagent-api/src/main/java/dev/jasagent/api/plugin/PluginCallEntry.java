package dev.jasagent.api.plugin;

import dev.jasagent.api.pass.PassDecision;

public record PluginCallEntry(
        String executionId,
        String userId,
        String pluginId,
        String phase,
        String argumentHash,
        String passId,
        PassDecision decision,
        String reason,
        boolean executed,
        boolean failed,
        String output) {
}
