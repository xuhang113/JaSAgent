package dev.jasagent.rag;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 按查询给候选段重新打相关性分。
 * 分数顺序必须和候选顺序一一对应，供质量门决定去留和名次。
 */
@FunctionalInterface
public interface Reranker {
    /**
     * 为每一段候选估计它和查询有多相关。
     *
     * @param query 用户查询原文
     * @param candidates 融合后留下的候选，顺序就是分数顺序
     * @return 与候选等长的分数；约定有效分落在 0 到 1
     */
    CompletionStage<List<Double>> score(String query, List<RerankCandidate> candidates);
}
