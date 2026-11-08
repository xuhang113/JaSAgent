package dev.jasagent.api.plugin;

import dev.jasagent.api.pass.Pass;

public record PluginResult(
        String executionId,
        String pluginId,
        boolean executed,
        boolean failed,
        String output,
        Pass pass) {
}
