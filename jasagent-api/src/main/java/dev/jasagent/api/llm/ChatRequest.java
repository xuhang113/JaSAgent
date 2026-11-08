package dev.jasagent.api.llm;

import java.util.List;

public record ChatRequest(
        LlmTask task,
        List<ChatMessage> messages,
        List<ToolDefinition> tools,
        String namedProvider,
        String executionId) {
    public ChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
