package dev.jasagent.rag;

public record RerankCandidate(String chunkId, String content, String title, String uri, String sourceScope) {
}
