package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ReciprocalRankFusion {
    private ReciprocalRankFusion() {
    }

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

    record Fused(String chunkId, double fusionScore, Map<String, PathScore> paths) {
        PathScore path(String name) {
            return paths.getOrDefault(name, PathScore.absent());
        }
    }
}
