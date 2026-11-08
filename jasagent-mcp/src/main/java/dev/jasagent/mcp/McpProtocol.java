package dev.jasagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;

final class McpProtocol {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private McpProtocol() {
    }

    static ObjectNode request(int id, String method, JsonNode params) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", id);
        node.put("method", method);
        if (params != null) {
            node.set("params", params);
        }
        return node;
    }

    static ObjectNode notification(String method) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("method", method);
        return node;
    }

    static JsonNode result(JsonNode response, String executionId) {
        if (response.hasNonNull("error")) {
            String message = response.path("error").path("message").asText("mcp 调用失败");
            throw new AgentException(ErrorCode.MCP, message, executionId, null);
        }
        return response.path("result");
    }

    static RemoteResult content(JsonNode result) {
        StringBuilder text = new StringBuilder();
        JsonNode blocks = result.get("content");
        if (blocks != null && blocks.isArray()) {
            for (JsonNode block : blocks) {
                if (!block.has("text")) {
                    continue;
                }
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(block.get("text").asText());
            }
        }
        return new RemoteResult(text.toString(), result.path("isError").asBoolean(false));
    }

    static List<RemoteTool> load(Exchange exchange) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", MAPPER.createObjectNode());
        ObjectNode info = MAPPER.createObjectNode();
        info.put("name", "jasagent");
        info.put("version", "0.1.0");
        params.set("clientInfo", info);
        JsonNode initialized = exchange.request("initialize", params, null);
        exchange.rememberProtocol(initialized.path("protocolVersion").asText("2024-11-05"));
        exchange.notify("notifications/initialized");
        List<RemoteTool> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            ObjectNode listParams = MAPPER.createObjectNode();
            if (cursor != null) {
                listParams.put("cursor", cursor);
            }
            JsonNode listed = exchange.request("tools/list", listParams, null);
            for (JsonNode tool : listed.path("tools")) {
                String name = tool.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                JsonNode schema = tool.get("inputSchema");
                String parameters = schema == null || schema.isNull() ? "{}" : schema.toString();
                tools.add(new RemoteTool(name, tool.path("description").asText(""), parameters));
            }
            String next = listed.path("nextCursor").asText("");
            if (next.isBlank()) {
                break;
            }
            cursor = next;
        }
        return List.copyOf(tools);
    }

    interface Exchange {
        JsonNode request(String method, JsonNode params, String executionId);

        void notify(String method);

        void rememberProtocol(String protocolVersion);
    }

    record RemoteTool(String name, String description, String parametersJson) {
    }

    record RemoteResult(String text, boolean error) {
    }
}
