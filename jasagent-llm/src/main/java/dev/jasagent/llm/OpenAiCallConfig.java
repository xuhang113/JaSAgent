package dev.jasagent.llm;

import java.time.Duration;
import java.util.Map;

/**
 * 一次 OpenAI 兼容调用需要的地址、密钥、模型、超时和思考开关。
 *
 * @param baseUrl 服务根地址，不含具体补全路径
 * @param apiKey 调用密钥；缺失时用空串，避免客户端拿到 null
 * @param modelName 默认模型名；单次请求仍可另选模型
 * @param timeout 这一次调用的最长等待时间
 * @param maxRetries 库自身的自动重试次数，本项目固定传 0
 * @param logRequests 是否让客户端库打印请求
 * @param logResponses 是否让客户端库打印响应
 * @param customParameters 附加到请求上的厂商参数，例如思考开关
 * @param returnThinking 为 true 时要求响应带回思考内容
 */
public record OpenAiCallConfig(
        String baseUrl,
        String apiKey,
        String modelName,
        Duration timeout,
        int maxRetries,
        boolean logRequests,
        boolean logResponses,
        Map<String, Object> customParameters,
        boolean returnThinking) {

    /**
     * DeepSeek 官方接口的根地址。
     */
    public static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    /**
     * 未单独指定模型时，DeepSeek 聊天使用的默认模型名。
     */
    public static final String DEEPSEEK_MODEL = "deepseek-flash";
    /**
     * Grok 官方 OpenAI 兼容接口的根地址。LangChain4j 会在它后面接聊天补全或 responses。
     */
    public static final String GROK_BASE_URL = "https://api.x.ai/v1";
    /**
     * 本进程调用 Grok 时使用的模型名。
     */
    public static final String GROK_MODEL = "grok-4.7";

    /**
     * 按设置生成 DeepSeek 聊天配置。思考开关决定附加参数是 enabled 还是 disabled。
     *
     * @param settings 含 DeepSeek 密钥、思考开关和超时的配置
     * @return 指向 DeepSeek 官方地址和默认模型的调用配置
     */
    public static OpenAiCallConfig deepSeek(LlmSettings settings) {
        boolean thinking = settings.deepseekThinking();
        Map<String, Object> custom = Map.of("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
        return new OpenAiCallConfig(
                DEEPSEEK_BASE_URL,
                emptyIfNull(settings.deepseekApiKey()),
                DEEPSEEK_MODEL,
                settings.timeout(),
                0,
                false,
                false,
                custom,
                thinking);
    }

    /**
     * 按设置生成网关聊天配置。不附带思考参数，也不要求返回思考。
     *
     * @param settings 含网关地址、密钥、聊天模型名和超时的配置
     * @return 指向该网关聊天模型的调用配置
     */
    public static OpenAiCallConfig gatewayChat(LlmSettings settings) {
        return new OpenAiCallConfig(
                settings.gatewayBaseUrl(),
                emptyIfNull(settings.gatewayApiKey()),
                settings.gatewayModel(),
                settings.timeout(),
                0,
                false,
                false,
                Map.of(),
                false);
    }

    /**
     * 按设置生成 Grok 聊天配置。不附带 DeepSeek 的思考参数。密钥只放进客户端，不写进日志开关。
     *
     * @param settings 含 Grok 密钥和超时的配置
     * @return 指向 https://api.x.ai/v1 和 grok-4.7 的调用配置
     */
    public static OpenAiCallConfig grok(LlmSettings settings) {
        return new OpenAiCallConfig(
                GROK_BASE_URL,
                emptyIfNull(settings.grokApiKey()),
                GROK_MODEL,
                settings.timeout(),
                0,
                false,
                false,
                Map.of(),
                false);
    }

    /**
     * 按设置生成网关嵌入配置，模型名用嵌入模型而不是聊天模型。
     *
     * @param settings 含网关地址、密钥、嵌入模型名和超时的配置
     * @return 指向该网关嵌入模型的调用配置
     */
    public static OpenAiCallConfig gatewayEmbed(LlmSettings settings) {
        return new OpenAiCallConfig(
                settings.gatewayBaseUrl(),
                emptyIfNull(settings.gatewayApiKey()),
                settings.gatewayEmbeddingModel(),
                settings.timeout(),
                0,
                false,
                false,
                Map.of(),
                false);
    }

    /**
     * 把缺失的密钥收成空串，避免客户端构造时收到 null。
     *
     * @param value 原始密钥或地址
     * @return 入参本身；null 时返回空串
     */
    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
