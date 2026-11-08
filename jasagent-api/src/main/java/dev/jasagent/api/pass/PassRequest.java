package dev.jasagent.api.pass;

public record PassRequest(String userId, String executionId, String toolName, String pluginId, String argumentsHash) {
}
