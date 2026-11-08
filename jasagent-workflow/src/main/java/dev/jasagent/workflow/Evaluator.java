package dev.jasagent.workflow;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface Evaluator {
    CompletionStage<EvaluationOutcome> evaluate(EvaluationQuery query);

    record EvaluationQuery(String executionId, String evaluator, String output, List<String> toolTexts) {
    }

    record EvaluationOutcome(boolean passed, String detail) {
    }
}
