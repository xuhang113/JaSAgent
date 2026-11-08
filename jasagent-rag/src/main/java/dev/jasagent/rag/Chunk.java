package dev.jasagent.rag;

public record Chunk(String docId, String chunkId, String text, int start, int end, String scope, String uri) {
}
