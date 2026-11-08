package dev.jasagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ToolCall;
import dev.jasagent.api.llm.ToolDefinition;

import java.util.List;

public final class ChatPayloads {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatPayloads() {
    }

    public static String deepseek(List<ChatMessage> messages, List<ToolDefinition> tools, boolean thinking) {
        ObjectNode root = base("deepseek-flash", messages, tools);
        root.putObject("thinking").put("type", thinking ? "enabled" : "disabled");
        return root.toString();
    }

    public static String gateway(String model, List<ChatMessage> messages, List<ToolDefinition> tools) {
        return base(model, messages, tools).toString();
    }

    private static ObjectNode base(String model, List<ChatMessage> messages, List<ToolDefinition> tools) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        ArrayNode messageNodes = root.putArray("messages");
        if (messages != null) {
            for (ChatMessage message : messages) {
                ObjectNode node = messageNodes.addObject();
                node.put("role", message.role());
                node.put("content", message.content() == null ? "" : message.content());
                if (!message.toolCalls().isEmpty()) {
                    ArrayNode calls = node.putArray("tool_calls");
                    for (ToolCall call : message.toolCalls()) {
                        ObjectNode callNode = calls.addObject();
                        callNode.put("id", call.id());
                        callNode.put("type", "function");
                        callNode.putObject("function")
                                .put("name", call.name())
                                .put("arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson());
                    }
                }
            }
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolNodes = root.putArray("tools");
            for (ToolDefinition tool : tools) {
                ObjectNode toolNode = toolNodes.addObject();
                toolNode.put("type", "function");
                ObjectNode function = toolNode.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description() == null ? "" : tool.description());
                if (tool.parametersJson() != null && !tool.parametersJson().isBlank()) {
                    try {
                        function.set("parameters", MAPPER.readTree(tool.parametersJson()));
                    } catch (Exception ex) {
                        function.put("parameters", tool.parametersJson());
                    }
                }
            }
        }
        return root;
    }
}
