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
}
