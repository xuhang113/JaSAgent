package dev.jasagent.core;

public record SubmitCommand(String userId, String sessionId, String workspace, String input) {
}
