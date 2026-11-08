package dev.jasagent.api.pass;

import java.nio.file.Path;

public record PassRequest(
        String userId,
        String executionId,
        Path userDirectory,
        Path workspaceDirectory,
        String toolName,
        String pluginId,
        String argumentHash) {
    public PassRequest(String userId, String executionId, String toolName, String pluginId, String argumentsHash) {
        this(userId, executionId, null, null, toolName, pluginId, argumentsHash);
    }

    public String argumentsHash() {
        return argumentHash;
    }
}
