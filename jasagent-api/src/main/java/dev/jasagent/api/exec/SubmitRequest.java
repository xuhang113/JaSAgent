package dev.jasagent.api.exec;

public record SubmitRequest(String userId, String sessionId, String workspace, String workflowId, String input, String correlationKey) {
}
