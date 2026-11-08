package dev.jasagent.evaluation;

import dev.jasagent.api.workflow.EvalResult;

public final class TerminalEvaluation {
    public static final String EVALUATOR = "completion.nonempty";

    private TerminalEvaluation() {
    }

    public static TerminalCheck afterTerminal(String status, String output) {
        return new TerminalCheck(BuiltinEvaluators.completionNonempty(output));
    }

    public static String apply(String status, boolean required, EvalResult result) {
        if (!result.passed() && required) {
            return "FAILED";
        }
        return status;
    }

    public record TerminalCheck(EvalResult evaluation) {
    }
}
