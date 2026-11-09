package dev.jasagent.api.eval;

import java.util.concurrent.CompletionStage;

/**
 * 按标识检查一次执行的收尾文本。
 * 未知评估器应给出未通过的结果，而不是交回空。
 */
public interface Evaluator {
    /**
     * 用指定评估器检查这段输出。
     * {@code required} 为真且评估未通过时，调用方应把执行判为失败；评估通过时保持原状态。
     *
     * @param evaluator 评估器标识
     * @param executionId 被评估的执行
     * @param output 收尾文本
     * @param required 这次评估是否必需
     * @return 完成后得到是否通过和说明的阶段
     */
    CompletionStage<EvalResult> evaluate(String evaluator, String executionId, String output, boolean required);
}
