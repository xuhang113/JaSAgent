package dev.jasagent.evaluation;

import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatModel;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.workflow.EvalResult;

import java.util.List;

public final class BuiltinEvaluators {
    private BuiltinEvaluators() {
    }

    public static EvalResult completionNonempty(String output) {
        boolean passed = output != null && !output.isBlank();
        return new EvalResult(passed, TerminalEvaluation.EVALUATOR);
    }

    public static EvalResult toolSuccessRate(int successes, int total) {
        boolean passed = total == 0 || successes == total;
        return new EvalResult(passed, "tool.success.rate");
    }

    public static EvalResult judge(boolean enabled, ChatModel model, String output) {
        if (!enabled) {
            return new EvalResult(true, "llm.judge skipped");
        }
        model.complete(new ChatRequest(LlmTask.JUDGE, List.of(ChatMessage.text("user", output)), List.of(), null, "judge"))
                .toCompletableFuture()
                .join();
        return new EvalResult(true, "llm.judge");
    }
}
