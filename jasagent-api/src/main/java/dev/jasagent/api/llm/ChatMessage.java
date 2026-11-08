package dev.jasagent.api.llm;

import java.util.List;

public record ChatMessage(String role, String content, List<ToolCallRef> toolCalls) {
    public record ToolCallRef(String id, String name, String argumentsJson) {
    }
}
