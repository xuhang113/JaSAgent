package dev.jasagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ReasoningText {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReasoningText() {
    }

    public static String fromCompletionJson(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            return fromTree(JSON.readTree(json));
        } catch (Exception ex) {
            return "";
        }
    }

    static String fromTree(JsonNode root) {
        if (root == null || root.isNull()) {
            return "";
        }
        JsonNode message = root.path("choices").path(0).path("message");
        if (!message.isObject()) {
            message = root.path("message");
        }
        if (!message.isObject()) {
            message = root;
        }
        return first(
                text(message, "reasoning_content"),
                text(message, "reasoning"),
                text(message, "thinking"));
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return "";
        }
        return value.asText();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
