package dev.jasagent.evaluation;

public record EvaluationResult(
        String executionId,
        String evaluatorId,
        boolean passed,
        double score,
        String reason) {
}
