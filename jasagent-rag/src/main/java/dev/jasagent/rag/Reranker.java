package dev.jasagent.rag;

import java.util.List;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface Reranker {
    CompletionStage<List<Double>> score(String query, List<RerankCandidate> candidates);
}
