package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在重排之后丢掉残缺命中、按正文哈希去重，再用重排下限和条数上限决定交回哪些段。
 */
public final class QualityGate {
    /**
     * 质量门不保存阈值之外的实例状态，阈值由每次调用传入。
     * 副作用：不写入字段。
     */
    private QualityGate() {
    }

    /**
     * 筛出字段完整且重排分达到下限的命中。
     * 同一正文哈希只留重排分更高的一条，再按重排分、融合分和段标识排序；全部被丢掉时原因是 quality-empty。
     *
     * @param hits 重排后的原始命中，里面可以夹着缺出处或坏哈希的段
     * @param minRerank 重排分下限，等于该值的命中留下
     * @param topK 最多返回的条数；空引用表示不截断
     * @param required 最终零命中时是否把结果标记为失败
     * @return 去重并截断后的结果；没有合格命中时命中为空且原因为 quality-empty
     */
    public static RagResult apply(List<RagHit> hits, double minRerank, Integer topK, boolean required) {
        List<RagHit> complete = new ArrayList<>();
        for (RagHit hit : hits) {
            if (complete(hit)) {
                complete.add(hit);
            }
        }
        Map<String, RagHit> best = new LinkedHashMap<>();
        for (RagHit hit : complete) {
            RagHit previous = best.get(hit.contentHash());
            if (previous == null || better(hit, previous)) {
                best.put(hit.contentHash(), hit);
            }
        }
        List<RagHit> kept = new ArrayList<>();
        for (RagHit hit : best.values()) {
            if (hit.rerankScore() >= minRerank) {
                kept.add(hit);
            }
        }
        kept.sort(Comparator.comparingDouble(RagHit::rerankScore).reversed()
                .thenComparing(Comparator.comparingDouble(RagHit::fusionScore).reversed())
                .thenComparing(RagHit::chunkId));
        if (topK != null && kept.size() > topK) {
            kept = new ArrayList<>(kept.subList(0, Math.max(topK, 0)));
        }
        if (kept.isEmpty()) {
            return new RagResult(List.of(), "quality-empty", required);
        }
        return new RagResult(kept, null, required);
    }

    /**
     * 判断候选是不是比同正文哈希里已经留下的那条更该保留。
     * 先比重排分，再比融合分，还相同就留段标识字典序更小的一条。
     *
     * @param candidate 新看到的完整命中
     * @param current 同一正文哈希下已经留下的命中
     * @return 候选应替换当前命中时为真
     */
    private static boolean better(RagHit candidate, RagHit current) {
        int rerank = Double.compare(candidate.rerankScore(), current.rerankScore());
        if (rerank != 0) {
            return rerank > 0;
        }
        int fusion = Double.compare(candidate.fusionScore(), current.fusionScore());
        if (fusion != 0) {
            return fusion > 0;
        }
        return candidate.chunkId().compareTo(current.chunkId()) < 0;
    }

    /**
     * 检查命中的出处、区间、正文哈希、分数和三路名次是否都能对上。
     * 范围只能是 user 或 workspace，重排分必须是 0 到 1 的有限数，哈希必须等于正文的 SHA-256。
     *
     * @param hit 待检查的命中，空引用算不完整
     * @return 字段齐全且彼此一致时为真
     */
    private static boolean complete(RagHit hit) {
        if (hit == null) {
            return false;
        }
        if (!"user".equals(hit.sourceScope()) && !"workspace".equals(hit.sourceScope())) {
            return false;
        }
        if (blank(hit.uri()) || blank(hit.docId()) || blank(hit.chunkId()) || blank(hit.content())) {
            return false;
        }
        if (hit.start() < 0 || hit.end() <= hit.start()) {
            return false;
        }
        if (blank(hit.contentHash()) || !hit.contentHash().equals(Hashes.sha256(hit.content()))) {
            return false;
        }
        if (!Double.isFinite(hit.fusionScore()) || !Double.isFinite(hit.rerankScore())) {
            return false;
        }
        if (hit.rerankScore() < 0 || hit.rerankScore() > 1) {
            return false;
        }
        return pathOk(hit.lexical()) && pathOk(hit.vector()) && pathOk(hit.title());
    }

    /**
     * 检查某一路的名次和分数能不能放进命中。
     *
     * @param score 这一路的名次和分，空引用不合格
     * @return 名次不小于 0 且分数是有限数时为真
     */
    private static boolean pathOk(PathScore score) {
        return score != null && score.rank() >= 0 && Double.isFinite(score.score());
    }

    /**
     * 判断出处或正文字段是不是缺失。
     *
     * @param value 待检查的文本
     * @return 空引用或只有空白时为真
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
