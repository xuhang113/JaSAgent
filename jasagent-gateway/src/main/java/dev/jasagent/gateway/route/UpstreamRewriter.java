package dev.jasagent.gateway.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jasagent.gateway.agent.AgentInstance;
import org.springframework.http.HttpMethod;

/**
 * 改写从 agent 回来的 JSON。模型目录保留已有的 DeepSeek，并接受 grok-4.7。
 * 执行详情写上这次请求落到的实例编号和地址，页面用这两个字段显示。
 * 解析失败时原样返回，不把正文写进异常。
 */
public final class UpstreamRewriter {
    /**
     * DeepSeek 供应商标识。目录里已有的 DeepSeek 项保持原顺序。
     */
    public static final String DEEPSEEK = "deepseek";
    /**
     * 目录里没有任何 DeepSeek 时补上的模型 id。
     */
    public static final String DEEPSEEK_FLASH = "deepseek-flash";
    /**
     * grok 的供应商标识。保存会话时 modelProvider 用这个字符串。
     */
    public static final String GROK = "grok";
    /**
     * 接受的 grok 模型 id。
     */
    public static final String GROK_MODEL = "grok-4.7";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 禁止实例化。改写只通过静态方法完成。
     */
    private UpstreamRewriter() {
    }

    /**
     * 按路径决定是否改写正文。不是模型或执行详情时原样返回。
     *
     * @param method 请求方法
     * @param path 请求路径
     * @param body agent 返回的正文，可以是空白
     * @param instance 这次落到的 agent；执行详情需要它，模型目录可以没有
     * @return 改写后的 JSON；无法解析时是原来的正文
     */
    public static String rewrite(HttpMethod method, String path, String body, AgentInstance instance) {
        if (body == null || body.isBlank()) {
            return body;
        }
        try {
            if (GatewayPaths.models(method, path)) {
                return models(body);
            }
            if (GatewayPaths.execution(method, path) && instance != null) {
                return execution(body, instance);
            }
        } catch (Exception ex) {
            return body;
        }
        return body;
    }

    /**
     * 保留接口里的模型，没有 DeepSeek 时在最前补上 deepseek-flash，没有 grok-4.7 时加在末尾。
     * 已经有同供应商、同 id 的 grok-4.7 时不重复。其它供应商的项不动。
     *
     * @param body 模型接口的 JSON
     * @return 补全后的 JSON
     * @throws Exception 正文不是对象时由调用方退回原文
     */
    static String models(String body) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        if (!root.isObject()) {
            return body;
        }
        ObjectNode object = (ObjectNode) root;
        JsonNode current = object.get("models");
        ArrayNode models = current != null && current.isArray() ? (ArrayNode) current : MAPPER.createArrayNode();
        boolean hasDeepseek = false;
        boolean hasGrok = false;
        for (JsonNode item : models) {
            if (!item.isObject()) {
                continue;
            }
            String provider = text(item.get("provider"));
            String id = text(item.get("id"));
            if (DEEPSEEK.equals(provider)) {
                hasDeepseek = true;
            }
            if (GROK.equals(provider) && GROK_MODEL.equals(id)) {
                hasGrok = true;
            }
        }
        if (!hasDeepseek) {
            ArrayNode next = MAPPER.createArrayNode();
            next.add(model(DEEPSEEK_FLASH, DEEPSEEK, DEEPSEEK_FLASH));
            models.forEach(next::add);
            models = next;
        }
        if (!hasGrok) {
            models.add(model(GROK_MODEL, GROK, GROK_MODEL));
        }
        object.set("models", models);
        JsonNode fallback = object.get("defaultModel");
        if (fallback == null || fallback.asText().isBlank()) {
            object.put("defaultModel", DEEPSEEK_FLASH);
        }
        return MAPPER.writeValueAsString(object);
    }

    /**
     * 在执行详情上写 agentInstanceId 和 agentAddress。已有同名字段时用这次路由结果覆盖。
     *
     * @param body 执行详情 JSON
     * @param instance 这次落到的 agent
     * @return 带上实例和地址的 JSON
     * @throws Exception 正文不是对象时由调用方退回原文
     */
    static String execution(String body, AgentInstance instance) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        if (!root.isObject()) {
            return body;
        }
        ObjectNode object = (ObjectNode) root;
        object.put("agentInstanceId", instance.instanceId());
        object.put("agentAddress", instance.address());
        return MAPPER.writeValueAsString(object);
    }

    /**
     * 做一条模型项。id、provider、label 都写入，缺 label 时调用方已经给了 id。
     *
     * @param id 模型 id
     * @param provider 供应商
     * @param label 界面上的名字
     * @return JSON 对象
     */
    private static ObjectNode model(String id, String provider, String label) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("provider", provider);
        node.put("label", label);
        return node;
    }

    /**
     * 把 JSON 字段收成去掉空白的字符串。缺字段时是空串。
     *
     * @param node 字段节点，可以是空
     * @return 去掉空白后的文本
     */
    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").trim();
    }
}
