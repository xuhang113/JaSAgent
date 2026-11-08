package dev.jasagent.api.tool;

import dev.jasagent.api.pass.PassDecision;

public record ToolCallEntry(
        String executionId,
        String userId,
        String toolName,
        String argumentHash,
        String passId,
        PassDecision decision,
        String reason,
        boolean executed,
        String output) {
}
