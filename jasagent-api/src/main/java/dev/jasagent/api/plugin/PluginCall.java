package dev.jasagent.api.plugin;

import java.nio.file.Path;

public record PluginCall(
        String userId,
        String executionId,
        Path userDirectory,
        Path workspaceDirectory,
        String pluginId,
        PluginPhase phase,
        String arguments,
        String modelOutput,
        PluginFailure failure) {
}
