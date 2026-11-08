package dev.jasagent.evaluation;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatModel;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.LlmTask;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class EvaluationEngine {
    public static final String COMPLETION_NONEMPTY = "completion.nonempty";
    public static final String TOOL_SUCCESS_RATE = "tool.success.rate";
    public static final String LLM_JUDGE = "llm.judge";

    private static final String JUDGE_SYSTEM = "只回复一个 JSON 对象，包含布尔 passed、0 到 1 的 score 和字符串 reason。不要输出其他文字。";

    private final boolean judgeEnabled;
    private final ChatModel chatModel;

    public EvaluationEngine(boolean judgeEnabled, ChatModel chatModel) {
        this.judgeEnabled = judgeEnabled;
        this.chatModel = chatModel;
    }

    public CompletionStage<EvaluationOutcome> evaluate(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        String id = request.evaluatorId();
        if (COMPLETION_NONEMPTY.equals(id)) {
            return CompletableFuture.completedFuture(finish(request, nonempty(request)));
        }
        if (TOOL_SUCCESS_RATE.equals(id)) {
            return CompletableFuture.completedFuture(finish(request, toolRate(request)));
        }
        if (LLM_JUDGE.equals(id)) {
            return judge(request);
        }
        return CompletableFuture.completedFuture(finish(request, new EvaluationResult(
                request.executionId(), id, false, 0.0, "unknown-evaluator")));
    }

    public CompletionStage<EvaluationOutcome> afterTerminal(String executionId, RunStatus status, String completion) {
        if (!terminal(status)) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.INTERNAL, "尚未终态", executionId, null));
        }
        return evaluate(new EvaluationRequest(executionId, COMPLETION_NONEMPTY, false, status, completion, List.of()));
    }

    private CompletionStage<EvaluationOutcome> judge(EvaluationRequest request) {
        if (!judgeEnabled) {
            return CompletableFuture.completedFuture(finish(request, new EvaluationResult(
                    request.executionId(), LLM_JUDGE, false, 0.0, "judge-disabled")));
        }
        if (chatModel == null) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.INTERNAL, "评判路由不可用", request.executionId(), null));
        }
        ChatRequest chatRequest = new ChatRequest(
                LlmTask.JUDGE,
                List.of(ChatMessage.text("system", JUDGE_SYSTEM), ChatMessage.text("user", trajectory(request))),
                List.of(),
                null,
                request.executionId());
        return chatModel.complete(chatRequest).thenApply(response -> finish(request, JudgeVerdicts.toResult(
                request.executionId(), contentOf(response))));
    }

    private static String contentOf(ChatResponse response) {
        if (response == null || response.message() == null) {
            return null;
        }
        return response.message().content();
    }

    private static String trajectory(EvaluationRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("completion:\n");
        if (request.completion() != null) {
            builder.append(request.completion());
        }
        builder.append("\ntools:\n");
        for (ToolObservation tool : request.toolCalls()) {
            builder.append("- ").append(tool.name()).append(tool.succeeded() ? " success\n" : " failed\n");
        }
        return builder.toString();
    }

    private static EvaluationResult nonempty(EvaluationRequest request) {
        String text = request.completion();
        boolean passed = text != null && !text.trim().isEmpty();
        return new EvaluationResult(
                request.executionId(),
                COMPLETION_NONEMPTY,
                passed,
                passed ? 1.0 : 0.0,
                passed ? "nonempty" : "empty");
    }

    private static EvaluationResult toolRate(EvaluationRequest request) {
        int total = request.toolCalls().size();
        int success = 0;
        for (ToolObservation tool : request.toolCalls()) {
            if (tool.succeeded()) {
                success++;
            }
        }
        double score = total == 0 ? 1.0 : (double) success / total;
        return new EvaluationResult(
                request.executionId(),
                TOOL_SUCCESS_RATE,
                score == 1.0,
                score,
                "success=" + success + ";total=" + total);
    }

    private static EvaluationOutcome finish(EvaluationRequest request, EvaluationResult result) {
        RunStatus status = request.status();
        if (!result.passed() && request.required()) {
            status = RunStatus.FAILED;
        }
        return new EvaluationOutcome(result, status);
    }

    private static boolean terminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED
                || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED
                || status == RunStatus.SUSPENDED;
    }
}
