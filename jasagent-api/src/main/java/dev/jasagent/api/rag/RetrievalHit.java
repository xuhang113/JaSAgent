package dev.jasagent.api.rag;

import java.util.Map;

public record RetrievalHit(
        SourceScope sourceScope,
        String uri,
        String docId,
        String chunkId,
        int start,
        int end,
        String contentHash,
        String text,
        Map<String, Integer> channelRanks,
        Map<String, Double> channelScores,
        double fusionScore,
        Double rerankScore) {
}
