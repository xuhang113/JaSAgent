package dev.jasagent.api.workflow;

import dev.jasagent.api.dsl.WorkflowDefinition;

public record WorkflowRequest(String executionId, String sessionId, String userId, String workspace, String input, WorkflowDefinition workflow) {
}
