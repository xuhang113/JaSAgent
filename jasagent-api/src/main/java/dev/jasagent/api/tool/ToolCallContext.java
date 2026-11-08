package dev.jasagent.api.tool;

public record ToolCallContext(String userId, String executionId, String argumentsJson) {
}
