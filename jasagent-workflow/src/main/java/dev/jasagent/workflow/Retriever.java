package dev.jasagent.workflow;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface Retriever {
    CompletionStage<List<RetrievalHit>> retrieve(RetrievalQuery query);

    record RetrievalQuery(String executionId, String query, int topK, List<String> scopes) {
    }

    record RetrievalHit(String content) {
    }
}
