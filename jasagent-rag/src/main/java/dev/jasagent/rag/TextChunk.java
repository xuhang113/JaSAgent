package dev.jasagent.rag;

public record TextChunk(String uri, String title, String chunkId, int start, int end, String content) {
}
