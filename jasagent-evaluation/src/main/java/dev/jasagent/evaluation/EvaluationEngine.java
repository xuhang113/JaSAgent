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

/**
 * 按评估器标识检查完成文本、工具成功率，或在启用时请模型评判轨迹。
 */
public final class EvaluationEngine {
    /**
     * 检查完成文本是否还有非空白内容的评估器标识。
     */
    public static final String COMPLETION_NONEMPTY = "completion.nonempty";
    /**
     * 按工具调用成功次数占比打分的评估器标识。
     */
    public static final String TOOL_SUCCESS_RATE = "tool.success.rate";
    /**
     * 把轨迹交给模型、并解析其 JSON 裁决的评估器标识。
     */
    public static final String LLM_JUDGE = "llm.judge";

    /**
     * 要求模型只回复一个含 passed、score 和 reason 的 JSON 对象的系统提示。
     */
    private static final String JUDGE_SYSTEM = "只回复一个 JSON 对象，包含布尔 passed、0 到 1 的 score 和字符串 reason。不要输出其他文字。";

    private final boolean judgeEnabled;
    private final ChatModel chatModel;

    /**
     * 记下模型评判是否启用，以及启用时用来发起评判的对话模型。
     *
     * @param judgeEnabled 为真时才允许调用模型评判
     * @param chatModel 评判使用的对话模型；未启用时可以空着
     */
    public EvaluationEngine(boolean judgeEnabled, ChatModel chatModel) {
        this.judgeEnabled = judgeEnabled;
        this.chatModel = chatModel;
    }

    /**
     * 按请求里的评估器标识打分。未知标识不调用模型，直接给出未通过。
     *
     * @param request 执行、评估器和轨迹
     * @return 完成后的评估结果和可能被改写的运行状态
     */
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

    /**
     * 运行已经进入终态后，用非空完成文本评估器做一次非必选检查。尚未终态时直接失败。
     *
     * @param executionId 已结束的执行
     * @param status 当前运行状态，必须已是终态
     * @param completion 终态时的完成文本
     * @return 非必选的非空检查结果；空白文本不会把成功改成失败
     */
    public CompletionStage<EvaluationOutcome> afterTerminal(String executionId, RunStatus status, String completion) {
        if (!terminal(status)) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.INTERNAL, "尚未终态", executionId, null));
        }
        return evaluate(new EvaluationRequest(executionId, COMPLETION_NONEMPTY, false, status, completion, List.of()));
    }

    /**
     * 在评判已启用且模型可用时，把完成文本和工具轨迹发给评判任务，再解析回复。未启用时直接返回未通过。
     *
     * @param request 含完成文本和工具观察的评判请求
     * @return 模型裁决转换后的评估结局
     */
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

    /**
     * 取出模型回复中的正文。
     *
     * @param response 模型回复；没有消息时无法解析
     * @return 回复正文；回复或消息为空时为空
     */
    private static String contentOf(ChatResponse response) {
        if (response == null || response.message() == null) {
            return null;
        }
        return response.message().content();
    }

    /**
     * 把完成文本和每次工具的成败整理成给评判模型看的用户消息。
     *
     * @param request 含完成文本和工具观察的请求
     * @return 以 completion 和 tools 两段组成的轨迹文本
     */
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

    /**
     * 去掉首尾空白后还有文字则满分通过，否则零分并标明为空。
     *
     * @param request 含完成文本的请求
     * @return 非空检查的结果
     */
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

    /**
     * 用成功的工具调用数除以总数。没有工具调用时视为满分；只要有一次失败就不通过。
     *
     * @param request 含工具观察的请求
     * @return 成功率结果，原因里写明成功数和总数
     */
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

    /**
     * 组装评估结局。结果未通过且这次评估是必选时，把运行状态改成失败。
     *
     * @param request 提供原状态和是否必选
     * @param result 评估器已经算出的结果
     * @return 带有最终运行状态的结局
     */
    private static EvaluationOutcome finish(EvaluationRequest request, EvaluationResult result) {
        RunStatus status = request.status();
        if (!result.passed() && request.required()) {
            status = RunStatus.FAILED;
        }
        return new EvaluationOutcome(result, status);
    }

    /**
     * 判断运行状态是否已经结束，从而允许做终态后的评估。
     *
     * @param status 待判断的运行状态
     * @return 成功、失败、取消或挂起时为真；刚接收时为假
     */
    private static boolean terminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED
                || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED
                || status == RunStatus.SUSPENDED;
    }
}
