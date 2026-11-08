package dev.jasagent.api.persist;

public record Checkpoint(String executionId, String nodeId, int stepIndex, String stateJson) {
}
