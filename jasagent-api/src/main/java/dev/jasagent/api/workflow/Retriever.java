package dev.jasagent.api.workflow;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface Retriever {
    CompletionStage<RetrievalResult> retrieve(String query, int topK, List<String> scopes, String executionId);
}
