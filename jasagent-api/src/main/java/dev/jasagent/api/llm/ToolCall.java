package dev.jasagent.api.llm;

public record ToolCall(String id, String name, String argumentsJson) {
}
