package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QualityGate {
    private QualityGate() {
    }

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

    private static boolean pathOk(PathScore score) {
        return score != null && score.rank() >= 0 && Double.isFinite(score.score());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
