package dev.jasagent.rag;

import java.util.List;

public record RagResult(List<RagHit> hits, String reason, boolean required) {
    public RagResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public boolean failed() {
        return required && hits.isEmpty();
    }
}
