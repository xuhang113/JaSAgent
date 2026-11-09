package dev.jasagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.llm.LlmToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * 把模型写在正文或补全 JSON 里的工具调用还原成调用列表。
 */
public final class ToolCallText {
    /**
     * 解析工具调用 JSON 的映射器，全类共用。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 禁止实例化。解析只通过静态方法完成。
     */
    private ToolCallText() {
    }

    /**
     * 从一段正文里识别单个工具对象。认 tool 或 name，参数认 arguments 或 args；普通句子返回空列表。
     *
     * @param text 模型正文，可以包在三反引号里；null 或空白视为没有调用
     * @return 解析出的至多一个工具调用；形状不对或解析失败时返回空列表
     */
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

    /**
     * 从 OpenAI 兼容补全 JSON 的 tool_calls 数组读取函数调用。
     *
     * @param body 补全响应原文；不含 tool_calls 或无法解析时视为没有调用
     * @return 按响应顺序排列的工具调用；名字为空的项会跳过
     */
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

    /**
     * 读取对象字段的文本。字段缺失或为 null 时返回空串。
     *
     * @param node 含该字段的对象节点
     * @param field 字段名
     * @return 字段文本；不可用时返回空串
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
