package dev.jasagent.rag;

import java.util.List;

public record RagResult(List<Hit> hits, String reason) {
    public RagResult {
        hits = List.copyOf(hits);
    }
}
