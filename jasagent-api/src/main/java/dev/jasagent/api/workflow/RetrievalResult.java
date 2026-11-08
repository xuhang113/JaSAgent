package dev.jasagent.api.workflow;

import java.util.List;

public record RetrievalResult(List<String> hits, String reason) {
    public RetrievalResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
