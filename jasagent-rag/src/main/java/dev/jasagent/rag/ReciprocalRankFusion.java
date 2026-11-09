package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把多条召回路的名次合成一个融合分。
 * 某一路把段排得越靠前，它贡献的 1/(k+名次) 越大；没出现在该路的段不加这一路的分。
 */
final class ReciprocalRankFusion {
    /**
     * 融合过程不保留跨调用状态。
     * 副作用：不写入字段。
     */
    private ReciprocalRankFusion() {
    }

    /**
     * 按路名汇总每段的倒数排名分，并留下各路自己的名次和原始分。
     * 名次从 1 起算。结果按融合分从高到低，再按段标识字典序。
     *
     * @param paths 路名到该路排序结果的映射，路名会写进融合明细
     * @param k 平滑常数，名次的分母是 k 加名次
     * @return 至少被一路召回的段，已按融合分排好
     */
    static List<Fused> fuse(Map<String, List<Ranked>> paths, int k) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Map<String, PathScore>> detail = new HashMap<>();
        for (Map.Entry<String, List<Ranked>> entry : paths.entrySet()) {
            List<Ranked> list = entry.getValue();
            for (int i = 0; i < list.size(); i++) {
                Ranked row = list.get(i);
                int rank = i + 1;
                scores.merge(row.chunkId(), 1.0 / (k + rank), Double::sum);
                detail.computeIfAbsent(row.chunkId(), ignored -> new HashMap<>())
                        .put(entry.getKey(), new PathScore(rank, row.score()));
            }
        }
        List<Fused> fused = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            fused.add(new Fused(entry.getKey(), entry.getValue(), detail.getOrDefault(entry.getKey(), Map.of())));
        }
        fused.sort(Comparator.comparingDouble(Fused::fusionScore).reversed().thenComparing(Fused::chunkId));
        return fused;
    }

    /**
     * 一段在各路召回合并之后的名次和分数。
     *
     * @param chunkId 段标识
     * @param fusionScore 各路倒数排名贡献之和
     * @param paths 路名到该路名次和原始分；没出现的路不在映射里
     */
    record Fused(String chunkId, double fusionScore, Map<String, PathScore> paths) {
        /**
         * 取出某一路给这段的名次和原始分。
         *
         * @param name 路名，例如 lexical、vector 或 title
         * @return 该路的名次和分；这段没进该路时返回名次 0、分数 0
         */
        PathScore path(String name) {
            return paths.getOrDefault(name, PathScore.absent());
        }
    }
}
