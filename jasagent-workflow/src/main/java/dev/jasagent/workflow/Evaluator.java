package dev.jasagent.workflow;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 按评估器名字判断一段输出和工具文本是否通过。
 */
public interface Evaluator {
    /**
     * 对当前输出做一次评估。
     *
     * @param query 执行编号、评估器名、输出和工具文本
     * @return 完成后给出是否通过和说明的阶段
     */
    CompletionStage<EvaluationOutcome> evaluate(EvaluationQuery query);

    /**
     * 一次评估需要的执行编号、评估器、输出和工具文本。
     *
     * @param executionId 这次工作流执行的编号
     * @param evaluator 评估器名字
     * @param output 待评估的输出
     * @param toolTexts 已经拿到的工具结果
     */
    record EvaluationQuery(String executionId, String evaluator, String output, List<String> toolTexts) {
    }

    /**
     * 一次评估的结论。
     *
     * @param passed 评估是否通过
     * @param detail 给人看的评估说明
     */
    record EvaluationOutcome(boolean passed, String detail) {
    }
}
