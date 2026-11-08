package dev.jasagent.evaluation;

import java.util.List;

public record EvaluationRequest(
        String executionId,
        String evaluatorId,
        boolean required,
        RunStatus status,
        String completion,
        List<ToolObservation> toolCalls) {
    public EvaluationRequest {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
