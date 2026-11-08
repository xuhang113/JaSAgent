package dev.jasagent.rag;

import java.util.List;

@FunctionalInterface
public interface Reranker {
    List<ScoredHit> rerank(String query, List<Hit> hits);
}
