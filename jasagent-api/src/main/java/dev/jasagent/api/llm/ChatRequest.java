package dev.jasagent.api.llm;

import java.util.List;

public record ChatRequest(LlmTask task, List<ChatMessage> messages, String namedProvider, String executionId) {
}
