package dev.jasagent.api.llm;

public record LlmToolCall(String id, String name, String arguments) {
    public LlmToolCall {
        if (id == null) {
            id = "";
        }
        if (name == null) {
            name = "";
        }
        if (arguments == null) {
            arguments = "";
        }
    }
}
