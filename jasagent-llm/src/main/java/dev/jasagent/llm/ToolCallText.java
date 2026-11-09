package dev.jasagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.llm.LlmToolCall;

import java.util.ArrayList;
import java.util.List;

public final class ToolCallText {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolCallText() {
    }

    public static List<LlmToolCall> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start + 1, end).trim();
            }
        }
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return List.of();
        }
        try {
            JsonNode node = JSON.readTree(trimmed);
            String name = text(node, "tool");
            if (name.isBlank()) {
                name = text(node, "name");
            }
            if (name.isBlank()) {
                return List.of();
            }
            JsonNode arguments = node.get("arguments");
            if (arguments == null) {
                arguments = node.get("args");
            }
            String encoded = arguments == null || arguments.isNull()
                    ? "{}"
                    : arguments.isTextual() ? arguments.asText() : JSON.writeValueAsString(arguments);
            return List.of(new LlmToolCall(text(node, "id"), name, encoded));
        } catch (Exception ex) {
            return List.of();
        }
    }

    public static List<LlmToolCall> fromCompletionJson(String body) {
        if (body == null || body.isBlank() || !body.contains("tool_calls")) {
            return List.of();
        }
        try {
            JsonNode calls = JSON.readTree(body).path("choices").path(0).path("message").path("tool_calls");
            if (!calls.isArray()) {
                return List.of();
            }
            List<LlmToolCall> parsed = new ArrayList<>();
            for (JsonNode call : calls) {
                JsonNode function = call.path("function");
                String name = text(function, "name");
                if (name.isBlank()) {
                    name = text(call, "name");
                }
                if (name.isBlank()) {
                    continue;
                }
                String arguments = text(function, "arguments");
                if (arguments.isBlank()) {
                    arguments = text(call, "arguments");
                }
                parsed.add(new LlmToolCall(text(call, "id"), name, arguments.isBlank() ? "{}" : arguments));
            }
            return List.copyOf(parsed);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
