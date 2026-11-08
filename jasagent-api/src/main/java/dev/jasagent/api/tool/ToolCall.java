package dev.jasagent.api.tool;

public record ToolCall(String id, String name, String argumentsJson, String userId, String executionId) {
}
