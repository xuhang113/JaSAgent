package dev.jasagent.rag;

record IndexedChunk(
        RagScope scope,
        String uri,
        String docId,
        String chunkId,
        String title,
        int start,
        int end,
        String contentHash,
        String content) {
}
