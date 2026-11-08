package dev.jasagent.api.workflow;

import dev.jasagent.api.exec.ExecutionStatus;

public record WorkflowResult(String executionId, ExecutionStatus status, String output, String errorCode) {
}
