package dev.jasagent.api.plugin;

import dev.jasagent.api.dsl.PluginPhase;

public record PluginContext(
        String pluginId,
        PluginPhase phase,
        String executionId,
        String nodeId,
        String payload,
        HallucinationGuard guard) {
    public PluginContext(HallucinationGuard guard) {
        this(null, null, null, null, null, guard);
    }

    public PluginContext(String pluginId, PluginPhase phase, String executionId, String nodeId, String payload) {
        this(pluginId, phase, executionId, nodeId, payload, null);
    }
}
