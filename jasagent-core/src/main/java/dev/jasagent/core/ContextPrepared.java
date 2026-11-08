package dev.jasagent.core;

import dev.jasagent.api.llm.ChatMessage;

import java.util.List;

public record ContextPrepared(List<ChatMessage> messages, String summary) {
    public ContextPrepared {
        messages = messages == null ? List.of() : List.copyOf(messages);
        summary = summary == null ? "" : summary;
    }
}
