package dev.jasagent.rag;

public record RagHit(
        String sourceScope,
        String uri,
        String docId,
        String chunkId,
        int start,
        int end,
        String contentHash,
        String content,
        PathScore lexical,
        PathScore vector,
        PathScore title,
        double fusionScore,
        double rerankScore) {
}
