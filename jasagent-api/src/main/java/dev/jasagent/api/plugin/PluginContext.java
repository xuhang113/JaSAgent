package dev.jasagent.api.plugin;

public record PluginContext(String phase, String executionId, String nodeId, String answer, String evidence) {
}
