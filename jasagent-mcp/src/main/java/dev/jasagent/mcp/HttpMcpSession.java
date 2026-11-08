package dev.jasagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class HttpMcpSession implements McpProtocol.Exchange, AutoCloseable {
    private final URI endpoint;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient client;
    private final AtomicInteger ids = new AtomicInteger();
    private final Object lock = new Object();
    private List<McpProtocol.RemoteTool> tools = List.of();
    private String sessionId;
    private String protocolVersion;
    private boolean closed;

    private HttpMcpSession(URI endpoint, Map<String, String> headers, Duration timeout, HttpClient client) {
        this.endpoint = endpoint;
        this.headers = headers;
        this.timeout = timeout;
        this.client = client;
    }

    static HttpMcpSession open(McpTransport.Http transport, Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        HttpMcpSession session = new HttpMcpSession(transport.endpoint(), transport.headers(), timeout, client);
        try {
            session.tools = McpProtocol.load(session);
            return session;
        } catch (RuntimeException ex) {
            session.close();
            throw ex;
        }
    }

    List<McpProtocol.RemoteTool> tools() {
        return tools;
    }

    McpProtocol.RemoteResult callTool(String name, String argumentsJson, String executionId) {
        synchronized (lock) {
            ObjectNode params = McpProtocol.MAPPER.createObjectNode();
            params.put("name", name);
            try {
                params.set("arguments", McpProtocol.MAPPER.readTree(argumentsJson));
            } catch (Exception ex) {
                throw new AgentException(ErrorCode.MCP, "工具参数不是 JSON", executionId, ex);
            }
            return McpProtocol.content(request("tools/call", params, executionId));
        }
    }

    @Override
    public JsonNode request(String method, JsonNode params, String executionId) {
        synchronized (lock) {
            int id = ids.incrementAndGet();
            return McpProtocol.result(post(McpProtocol.request(id, method, params), id, true, executionId), executionId);
        }
    }

    @Override
    public void notify(String method) {
        synchronized (lock) {
            post(McpProtocol.notification(method), 0, false, null);
        }
    }

    @Override
    public void rememberProtocol(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        client.close();
    }

    private JsonNode post(ObjectNode message, int id, boolean expectBody, String executionId) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(McpProtocol.MAPPER.writeValueAsString(message), StandardCharsets.UTF_8));
            headers.forEach(builder::header);
            if (sessionId != null) {
                builder.header("Mcp-Session-Id", sessionId);
            }
            if (protocolVersion != null) {
                builder.header("MCP-Protocol-Version", protocolVersion);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
            int status = response.statusCode();
            if (!expectBody) {
                if (status >= 200 && status < 300) {
                    return McpProtocol.MAPPER.createObjectNode();
                }
                throw new AgentException(ErrorCode.MCP, "mcp 通知失败", executionId, null);
            }
            if (status < 200 || status >= 300) {
                throw new AgentException(ErrorCode.MCP, "mcp 请求失败", executionId, null);
            }
            return readBody(response, id, executionId);
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.MCP, ex.getMessage() == null ? "mcp 请求失败" : ex.getMessage(), executionId, ex);
        }
    }

    private JsonNode readBody(HttpResponse<String> response, int id, String executionId) throws Exception {
        String type = response.headers().firstValue("Content-Type").orElse("");
        String body = response.body() == null ? "" : response.body();
        JsonNode node = type.contains("text/event-stream") ? readEvent(body, id, executionId) : readJson(body, executionId);
        if (node.hasNonNull("id") && node.get("id").asInt() != id) {
            throw new AgentException(ErrorCode.MCP, "mcp 响应编号不一致", executionId, null);
        }
        return node;
    }

    private static JsonNode readJson(String body, String executionId) throws Exception {
        if (body.isBlank()) {
            throw new AgentException(ErrorCode.MCP, "mcp 响应为空", executionId, null);
        }
        return McpProtocol.MAPPER.readTree(body);
    }

    private static JsonNode readEvent(String body, int id, String executionId) throws Exception {
        StringBuilder data = new StringBuilder();
        JsonNode matched = null;
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("data:")) {
                String piece = line.substring(5).stripLeading();
                if (!piece.isEmpty()) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(piece);
                }
                continue;
            }
            if (line.isBlank() && !data.isEmpty()) {
                matched = prefer(matched, McpProtocol.MAPPER.readTree(data.toString()), id);
                data.setLength(0);
            }
        }
        if (!data.isEmpty()) {
            matched = prefer(matched, McpProtocol.MAPPER.readTree(data.toString()), id);
        }
        if (matched == null) {
            throw new AgentException(ErrorCode.MCP, "mcp 事件流没有结果", executionId, null);
        }
        return matched;
    }

    private static JsonNode prefer(JsonNode current, JsonNode candidate, int id) {
        if (!candidate.has("result") && !candidate.has("error")) {
            return current;
        }
        if (candidate.hasNonNull("id") && candidate.get("id").asInt() != id) {
            return current;
        }
        return candidate;
    }
}
