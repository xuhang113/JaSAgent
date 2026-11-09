package dev.jasagent.evaluation;

import java.util.List;

/**
 * 交给评估器的一次输入，包含完成文本、工具观察和评估前的运行状态。
 *
 * @param executionId 被评估的执行
 * @param evaluatorId 要运行的评估器标识
 * @param required 未通过时是否把运行状态改成失败
 * @param status 评估前的运行状态
 * @param completion 完成文本，可以是空白
 * @param toolCalls 观察到的工具调用
 */
public record EvaluationRequest(
        String executionId,
        String evaluatorId,
        boolean required,
        RunStatus status,
        String completion,
        List<ToolObservation> toolCalls) {
    /**
     * 把空的工具观察列表收成不可变空列表，避免评估过程遇到空引用。
     *
     * @param executionId 被评估的执行
     * @param evaluatorId 要运行的评估器标识
     * @param required 未通过时是否把运行状态改成失败
     * @param status 评估前的运行状态
     * @param completion 完成文本
     * @param toolCalls 观察到的工具调用；空则换成空列表
     */
    public EvaluationRequest {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
