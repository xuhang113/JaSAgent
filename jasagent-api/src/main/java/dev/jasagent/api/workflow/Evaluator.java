package dev.jasagent.api.workflow;

import java.util.concurrent.CompletionStage;

public interface Evaluator {
    CompletionStage<EvalResult> evaluate(String name, String executionId, String output);
}
