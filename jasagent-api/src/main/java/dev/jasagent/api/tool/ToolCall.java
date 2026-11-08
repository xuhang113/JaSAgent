package dev.jasagent.api.tool;

import java.nio.file.Path;
import java.util.Map;

public record ToolCall(
        String userId,
        String executionId,
        Path userDirectory,
        Path workspaceDirectory,
        String name,
        Map<String, String> arguments) {
    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
