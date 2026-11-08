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
    public Pass(String passId, PassDecision decision, String reason) {
        this(passId, null, null, null, null, null, decision, reason);
    }

    public Pass(PassDecision decision, String reason) {
        this(null, null, null, null, null, null, decision, reason);
    }
}
