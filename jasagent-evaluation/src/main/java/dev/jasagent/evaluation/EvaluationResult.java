package dev.jasagent.evaluation;

/**
 * 一个评估器对某次执行给出的通过与否、分数和原因。
 *
 * @param executionId 被评估的执行
 * @param evaluatorId 给出这份结果的评估器标识
 * @param passed 是否通过
 * @param score 零到一之间的分数
 * @param reason 可展示的原因
 */
public record EvaluationResult(
        String executionId,
        String evaluatorId,
        boolean passed,
        double score,
        String reason) {
}
