package dev.jasagent.api.plugin;

import dev.jasagent.api.dsl.PluginPhase;

public record PluginContext(String pluginId, PluginPhase phase, String executionId, String nodeId, String payload) {
}
