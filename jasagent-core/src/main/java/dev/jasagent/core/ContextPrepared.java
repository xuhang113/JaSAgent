package dev.jasagent.core;

import dev.jasagent.api.llm.ChatMessage;

import java.util.List;

public record ContextPrepared(List<ChatMessage> messages) {
}
