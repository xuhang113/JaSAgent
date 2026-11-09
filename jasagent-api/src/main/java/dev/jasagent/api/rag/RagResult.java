package dev.jasagent.api.rag;

import java.util.List;

/**
 * 一次检索交回的命中列表，以及这批结果为何是现在这样。
 * 命中被质量校验清空时，{@code reason} 写明原因，列表为空。
 *
 * @param hits 按重排顺序留下的命中；一条不剩时为空列表
 * @param reason 对这批结果的说明；全部被丢掉时用来写明原因
 */
public record RagResult(List<RetrievalHit> hits, String reason) {
}
