package dev.jasagent.api.eval;

import java.util.concurrent.CompletionStage;

public interface Evaluator {
    CompletionStage<EvalResult> evaluate(String evaluator, String executionId, String output, boolean required);
}
