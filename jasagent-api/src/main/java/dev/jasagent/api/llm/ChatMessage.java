package dev.jasagent.api.llm;

import java.util.List;

public record ChatMessage(String role, String content, List<ToolCall> toolCalls) {
    public ChatMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage text(String role, String content) {
        return new ChatMessage(role, content, List.of());
    }
}
