package dev.jasagent.api.plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 核对模型输出有没有脱离已有材料。
 * 默认的落地判断不看材料，直接认为没有落地。
 */
public interface HallucinationGuard {
    /**
     * 按这次执行核对模型输出。
     * 通过时阶段正常结束；发现问题时阶段以异常结束。
     *
     * @param executionId 产出这段文字的执行
     * @param modelOutput 待核对的模型输出
     * @return 核对结束后完成的阶段
     */
    CompletionStage<Void> check(String executionId, String modelOutput);

    /**
     * 判断回答能否被证据支撑。
     * 默认实现不比较回答和证据，直接以假完成，表示未视为已经落地。
     *
     * @param answer 模型给出的回答
     * @param evidence 用来对照的证据文本
     * @param executionId 这次判断所属的执行
     * @return 完成后得到是否落地的阶段；默认结果为假
     */
    default CompletionStage<Boolean> grounded(String answer, String evidence, String executionId) {
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }
}
