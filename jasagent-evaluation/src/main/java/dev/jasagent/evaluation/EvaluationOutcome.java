package dev.jasagent.evaluation;

/**
 * 评估结果，以及评估之后应当对外呈现的运行状态。
 *
 * @param result 评估器给出的分数和原因
 * @param status 评估后的运行状态；必选评估未通过时会变成失败
 */
public record EvaluationOutcome(EvaluationResult result, RunStatus status) {
}
