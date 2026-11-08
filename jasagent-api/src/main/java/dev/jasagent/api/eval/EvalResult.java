package dev.jasagent.api.eval;

public record EvalResult(String evaluator, boolean passed, String detail) {
}
