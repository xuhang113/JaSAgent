package dev.jasagent.api.pass;

public record Pass(
        String passId,
        String userId,
        String executionId,
        String toolName,
        String pluginId,
        String argumentHash,
        PassDecision decision,
        String reason) {
}
