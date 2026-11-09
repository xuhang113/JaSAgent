package dev.jasagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 从聊天补全的 JSON 里抽出模型的思考文本，优先用 reasoning_content。
 */
public final class ReasoningText {
    /**
     * 解析补全响应 JSON 的映射器，全类共用。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 禁止实例化。解析只通过静态方法完成。
     */
    private ReasoningText() {
    }

    /**
     * 从一整段补全 JSON 里读取思考文本。空白、残缺或没有思考字段时返回空串。
     *
     * @param json 聊天补全的响应原文；null 或空白视为没有思考
     * @return 找到的思考文本；没有或解析失败时返回空串
     */
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

    /**
     * 从已解析的补全树里取思考。先看 choices 里的 message，再退回根上的 message 或根本身。
     *
     * @param root 补全 JSON 的根节点；null 视为没有思考
     * @return reasoning_content、reasoning、thinking 中第一个有内容的文本，否则空串
     */
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

    /**
     * 读取节点上的一个文本字段。字段缺失、为 null 或不是字符串时返回空串。
     *
     * @param node 要读取的对象节点；null 视为没有该字段
     * @param field 字段名，例如 reasoning_content
     * @return 字段里的文本；不可用时返回空串
     */
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

    /**
     * 返回第一段非空白文本，用来在多个思考字段之间按优先级取值。
     *
     * @param values 按优先级排列的候选文本
     * @return 第一段有内容的文本；全部空白时返回空串
     */
    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
