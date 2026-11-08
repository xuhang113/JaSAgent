package dev.jasagent.api.task;

public record WorkItem(
        String taskId,
        String executionId,
        String sessionId,
        String correlationKey,
        String targetText,
        String workflowId,
        String input) {
}
