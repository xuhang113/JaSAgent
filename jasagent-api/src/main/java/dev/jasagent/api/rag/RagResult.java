package dev.jasagent.api.rag;

import java.util.List;

public record RagResult(List<RetrievalHit> hits, String reason) {
}
