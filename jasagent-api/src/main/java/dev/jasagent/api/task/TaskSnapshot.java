package dev.jasagent.api.task;

public record TaskSnapshot(String taskId, String agentId, AgentRole role, String status, WorkItem item) {
}
