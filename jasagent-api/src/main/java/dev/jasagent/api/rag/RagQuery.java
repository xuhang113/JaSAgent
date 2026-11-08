package dev.jasagent.api.rag;

import java.util.List;

public record RagQuery(String executionId, String userId, String workspace, String query, int topK, List<SourceScope> scopes) {
}
