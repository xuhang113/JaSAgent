package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QualityFilter {
    private QualityFilter() {
    }

    public static RagResult apply(List<Hit> hits, double minRerank) {
        Map<String, Hit> byHash = new LinkedHashMap<>();
        for (Hit hit : hits) {
            if (!complete(hit) || hit.rerankScore() == null || hit.rerankScore() < minRerank) {
                continue;
            }
            Hit existing = byHash.get(hit.contentHash());
            if (existing == null || hit.rerankScore() > existing.rerankScore()) {
                byHash.put(hit.contentHash(), hit);
            }
        }
        List<Hit> kept = new ArrayList<>(byHash.values());
        kept.sort(Comparator.comparingDouble(Hit::rerankScore).reversed());
        if (kept.isEmpty()) {
            return new RagResult(List.of(), "quality-empty");
        }
        return new RagResult(kept, null);
    }

    private static boolean complete(Hit hit) {
        return present(hit.sourceScope())
                && present(hit.uri())
                && present(hit.docId())
                && present(hit.chunkId())
                && present(hit.contentHash())
                && hit.start() >= 0
                && hit.end() >= hit.start();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
