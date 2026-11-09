package dev.jasagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 组装和解析 MCP 使用的 JSON-RPC 报文。
 * 初始化之后发已初始化通知，再按页拉取远程工具，空名字的工具会被跳过。
 */
final class McpProtocol {
    /** 读写 JSON-RPC 报文共用的映射器，请求、响应和参数模式都走它。 */
    static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 报文辅助不保留跨调用状态。
     * 副作用：不写入字段。
     */
    private McpProtocol() {
    }

    /**
     * 组装一条带编号的 JSON-RPC 2.0 请求。
     * 参数为空时不写 params 字段。
     *
     * @param id 请求编号，用来和响应配对
     * @param method MCP 方法名，例如 initialize 或 tools/call
     * @param params 方法参数，可以为空
     * @return 还没发送的请求对象
     */
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

    /**
     * 组装一条不带编号的 JSON-RPC 通知。
     *
     * @param method 通知的方法名
     * @return 还没发送的通知对象
     */
    static ObjectNode notification(String method) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("method", method);
        return node;
    }

    /**
     * 从响应里取出 result；响应带 error 时抛出 MCP 异常。
     *
     * @param response 服务器返回的报文
     * @param executionId 当前执行标识，写进异常；没有时可以为空
     * @return result 节点；没有该字段时是缺失节点
     */
    static JsonNode result(JsonNode response, String executionId) {
        if (response.hasNonNull("error")) {
            String message = response.path("error").path("message").asText("mcp 调用失败");
            throw new AgentException(ErrorCode.MCP, message, executionId, null);
        }
        return response.path("result");
    }

    /**
     * 把工具结果里的 text 内容块按行拼起来。
     * 没有 text 的块被跳过。isError 缺省当成没有错误。
     *
     * @param result tools/call 的 result 节点
     * @return 拼好的文本，以及服务器是否把这次调用标成错误
     */
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

    /**
     * 完成初始化握手并拉回全部工具页。
     * 客户端报自己是 jasagent。协议版本先按 2024-11-05 发送，再改记服务器返回的版本。工具列表最多翻 20 页，没有参数模式时记成空对象。
     *
     * @param exchange 已经连上的会话
     * @return 名字非空的远程工具；没有工具时为空列表
     */
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

    /**
     * 一条 MCP 会话对握手和调用暴露的三个动作。
     * HTTP 和标准输入输出各自实现发送方式。
     */
    interface Exchange {
        /**
         * 发送带编号的请求并取回整段响应。
         *
         * @param method MCP 方法名
         * @param params 方法参数，可以为空
         * @param executionId 当前执行标识，失败时写进异常
         * @return 含 result 或 error 的响应报文
         */
        JsonNode request(String method, JsonNode params, String executionId);

        /**
         * 发送不带编号的通知，不等待结果报文。
         * 副作用：把通知写到当前传输；发送失败时抛出 MCP 异常。
         *
         * @param method 通知的方法名
         */
        void notify(String method);

        /**
         * 记下初始化响应里的协议版本，供之后的请求使用。
         * 副作用：由实现决定是否保存；不需要请求头的传输可以忽略它。
         *
         * @param protocolVersion 服务器返回的协议版本
         */
        void rememberProtocol(String protocolVersion);
    }

    /**
     * 服务器公布的一个工具。
     *
     * @param name 服务器上的工具名，不含本地注册前缀
     * @param description 给模型看的说明
     * @param parametersJson 输入参数的 JSON 模式；服务器没给时是空对象
     */
    record RemoteTool(String name, String description, String parametersJson) {
    }

    /**
     * 一次远程工具调用解出来的文本和错误标志。
     *
     * @param text 内容块拼成的文本
     * @param error 服务器是否把这次调用标成错误
     */
    record RemoteResult(String text, boolean error) {
    }
}
