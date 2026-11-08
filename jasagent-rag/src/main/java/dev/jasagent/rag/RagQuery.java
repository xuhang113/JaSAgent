package dev.jasagent.rag;

import java.util.Set;

public record RagQuery(String text, Set<RagScope> scopes, Integer topK, boolean required) {
}
