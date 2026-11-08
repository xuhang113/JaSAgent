package dev.jasagent.rag;

public record Hit(
        String sourceScope,
        String uri,
        String docId,
        String chunkId,
        int start,
        int end,
        String contentHash,
        String text,
        double fusionScore,
        Double rerankScore) {
    Hit withRerank(double score) {
        return new Hit(sourceScope, uri, docId, chunkId, start, end, contentHash, text, fusionScore, score);
    }

    Hit withFusion(double score) {
        return new Hit(sourceScope, uri, docId, chunkId, start, end, contentHash, text, score, rerankScore);
    }
}
