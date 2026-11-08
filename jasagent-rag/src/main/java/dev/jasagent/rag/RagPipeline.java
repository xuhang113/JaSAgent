package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RagPipeline {
    private RagPipeline() {
    }

    public static RagResult rerankThenFilter(List<Hit> fused, Reranker reranker, double minRerank) {
        List<Hit> ordered = new ArrayList<>(fused);
        ordered.sort(Comparator.comparingDouble(Hit::fusionScore).reversed());
        if (ordered.size() > 20) {
            ordered = new ArrayList<>(ordered.subList(0, 20));
        }
        List<Hit> scored = new ArrayList<>();
        for (ScoredHit score : reranker.rerank("query", ordered)) {
            for (Hit hit : ordered) {
                if (hit.chunkId().equals(score.chunkId())) {
                    scored.add(hit.withRerank(score.score()));
                }
            }
        }
        return QualityFilter.apply(scored, minRerank);
    }
}
