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

/**
 * 用 HTTP POST 维持一条 MCP 会话。
 * 响应可以是单个 JSON，也可以是事件流；会话编号和协议版本会在后续请求里带回。
 */
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

    /**
     * 记住端点、额外请求头、超时和已经建好的客户端。
     *
     * @param endpoint JSON-RPC 地址
     * @param headers 每次请求都附带的请求头
     * @param timeout 单次请求的最长等待
     * @param client 发送请求的 HTTP 客户端
     */
    private HttpMcpSession(URI endpoint, Map<String, String> headers, Duration timeout, HttpClient client) {
        this.endpoint = endpoint;
        this.headers = headers;
        this.timeout = timeout;
        this.client = client;
    }

    /**
     * 建立客户端并完成握手。
     * 副作用：握手失败时关闭这次会话再把异常抛出。
     *
     * @param transport 端点和额外请求头
     * @param timeout 连接和单次请求的最长等待
     * @return 已经拉回工具列表的会话
     */
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

    /**
     * 返回握手时拉到的远程工具。
     *
     * @return 远程工具列表，顺序与服务器分页结果一致
     */
    List<McpProtocol.RemoteTool> tools() {
        return tools;
    }

    /**
     * 按服务器上的原名调用工具。
     * 同一条会话上的调用串行发送。参数不是 JSON 时抛出 MCP 异常，不发 tools/call。
     *
     * @param name 服务器上的工具名
     * @param argumentsJson 工具参数的 JSON 文本
     * @param executionId 当前执行标识
     * @return 远程文本，以及服务器是否把它标成错误
     */
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

    /**
     * 分配请求编号，发送报文并取出 result。
     * 副作用：占用会话锁；响应头里若有会话编号，会记到这条会话上。
     *
     * @param method MCP 方法名
     * @param params 方法参数，可以为空
     * @param executionId 当前执行标识
     * @return 响应里的 result 节点
     */
    @Override
    public JsonNode request(String method, JsonNode params, String executionId) {
        synchronized (lock) {
            int id = ids.incrementAndGet();
            return McpProtocol.result(post(McpProtocol.request(id, method, params), id, true, executionId), executionId);
        }
    }

    /**
     * 发送不带编号的通知，只要成功状态码，不解析结果报文。
     * 副作用：占用会话锁并发送通知；可能从响应头记下会话编号。失败时抛出 MCP 异常。
     *
     * @param method 通知的方法名
     */
    @Override
    public void notify(String method) {
        synchronized (lock) {
            post(McpProtocol.notification(method), 0, false, null);
        }
    }

    /**
     * 记下协议版本，让之后的请求带上 MCP-Protocol-Version。
     * 副作用：改写会话上保存的协议版本。
     *
     * @param protocolVersion 初始化响应里的协议版本
     */
    @Override
    public void rememberProtocol(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    /**
     * 关闭 HTTP 客户端。
     * 副作用：已关闭过则直接返回；否则标记关闭并关闭客户端。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        client.close();
    }

    /**
     * 把报文 POST 到端点，并按是否需要响应体解释状态码。
     * 副作用：若响应带回 Mcp-Session-Id，就记到这条会话上。通知只要 2xx；正式请求的非 2xx 会失败。
     *
     * @param message 要发送的 JSON-RPC 报文
     * @param id 期望响应使用的编号；通知传 0
     * @param expectBody 为真时必须读到结果报文，为假时只看状态码
     * @param executionId 当前执行标识，失败时写进异常
     * @return 响应报文；不需要响应体且状态成功时是空对象
     */
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

    /**
     * 按内容类型把响应体读成报文，并核对响应编号。
     * 事件流走事件解析，其它内容按单个 JSON 解析。编号存在但不一致时失败。
     *
     * @param response 已经收到的 HTTP 响应
     * @param id 这次请求发出的编号
     * @param executionId 当前执行标识
     * @return 对上编号的响应报文
     */
    private JsonNode readBody(HttpResponse<String> response, int id, String executionId) throws Exception {
        String type = response.headers().firstValue("Content-Type").orElse("");
        String body = response.body() == null ? "" : response.body();
        JsonNode node = type.contains("text/event-stream") ? readEvent(body, id, executionId) : readJson(body, executionId);
        if (node.hasNonNull("id") && node.get("id").asInt() != id) {
            throw new AgentException(ErrorCode.MCP, "mcp 响应编号不一致", executionId, null);
        }
        return node;
    }

    /**
     * 把单个 JSON 响应体解析成报文。
     *
     * @param body 响应正文
     * @param executionId 当前执行标识，正文为空白时写进异常
     * @return 解析出的报文
     */
    private static JsonNode readJson(String body, String executionId) throws Exception {
        if (body.isBlank()) {
            throw new AgentException(ErrorCode.MCP, "mcp 响应为空", executionId, null);
        }
        return McpProtocol.MAPPER.readTree(body);
    }

    /**
     * 从事件流里挑出编号匹配、并且带结果或错误的那条报文。
     * 以 data 开头的行拼成一个事件，空行结束该事件。找不到可用事件时失败。
     *
     * @param body 事件流正文
     * @param id 这次请求发出的编号
     * @param executionId 当前执行标识
     * @return 选中的响应报文
     */
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

    /**
     * 在已经选中的报文和新事件之间选择。
     * 新事件没有结果也没有错误，或者编号对不上时，保留原来的选择。
     *
     * @param current 目前选中的报文，可以还没有
     * @param candidate 刚解析出的一个事件
     * @param id 这次请求发出的编号
     * @return 更该保留的那条报文
     */
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
