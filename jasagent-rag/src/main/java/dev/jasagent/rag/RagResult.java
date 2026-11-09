package dev.jasagent.rag;

import java.util.List;

/**
 * 一次检索交回调用方的结果。
 *
 * @param hits 通过质量门的命中，按重排分排好
 * @param reason 没有可用命中时的原因；有命中时为空
 * @param required 调用方是否把零命中当成这次检索失败
 */
public record RagResult(List<RagHit> hits, String reason, boolean required) {
    /**
     * 把命中收成不可变列表，避免调用方事后改结果。
     * 副作用：命中为空引用时改成空列表，否则复制一份不可变列表。
     *
     * @param hits 原始命中，可以为空
     * @param reason 空结果原因，有命中时可以为空
     * @param required 零命中是否算失败
     */
    public RagResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    /**
     * 判断这次检索是不是被调用方当成失败。
     * 只有要求必须有命中、并且一条都没留下时才算失败。
     *
     * @return 必选且命中为空时为真
     */
    public boolean failed() {
        return required && hits.isEmpty();
    }
}
