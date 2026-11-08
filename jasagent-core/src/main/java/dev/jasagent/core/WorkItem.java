package dev.jasagent.core;

public record WorkItem(
        String taskId,
        String executionId,
        String sessionId,
        String correlationKey,
        String targetText,
        String workflowJson,
        String input) {
}
