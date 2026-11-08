package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReciprocalRankFusion {
    public static final int K = 60;

    private ReciprocalRankFusion() {
    }

    public static List<Hit> fuse(Map<String, List<String>> channels) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> ranking : channels.values()) {
            for (int index = 0; index < ranking.size(); index++) {
                String id = ranking.get(index);
                int rank = index + 1;
                scores.merge(id, 1.0 / (K + rank), Double::sum);
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            hits.add(new Hit("user", "uri-" + entry.getKey(), "doc", entry.getKey(), 0, 1, "hash-" + entry.getKey(), entry.getKey(), entry.getValue(), 1.0));
        }
        hits.sort(Comparator.comparingDouble(Hit::fusionScore).reversed());
        return hits;
    }
}
