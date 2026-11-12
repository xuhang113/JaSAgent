package dev.jasagent.llm;

import java.time.Duration;
import java.util.Map;

/**
 * 一次进程里模型路由要用的密钥、网关地址和调用超时。
 *
 * @param deepseekApiKey DeepSeek 的访问密钥；空表示没有配置 DeepSeek
 * @param deepseekThinking 为 true 时向 DeepSeek 打开思考过程
 * @param gatewayBaseUrl 自建网关的根地址；空白表示未配置网关
 * @param gatewayApiKey 访问网关用的密钥
 * @param gatewayModel 网关聊天要调用的模型名
 * @param gatewayEmbeddings 为 true 时允许走网关做向量嵌入
 * @param gatewayEmbeddingModel 网关嵌入模型名
 * @param timeout 单次模型调用的最长等待时间
 * @param grokApiKey Grok 的访问密钥；空表示没有配置 Grok。不会出现在 {@link #toString()} 里
 */
public record LlmSettings(
        String deepseekApiKey,
        boolean deepseekThinking,
        String gatewayBaseUrl,
        String gatewayApiKey,
        String gatewayModel,
        boolean gatewayEmbeddings,
        String gatewayEmbeddingModel,
        Duration timeout,
        String grokApiKey) {

    /**
     * 超时没给出时改成 60 秒，保证后面的客户端总能拿到一个有限的等待上限。
     *
     * @param deepseekApiKey DeepSeek 的访问密钥，这里原样保留
     * @param deepseekThinking 是否打开 DeepSeek 思考，这里原样保留
     * @param gatewayBaseUrl 网关根地址，这里原样保留
     * @param gatewayApiKey 网关密钥，这里原样保留
     * @param gatewayModel 网关聊天模型名，这里原样保留
     * @param gatewayEmbeddings 是否启用网关嵌入，这里原样保留
     * @param gatewayEmbeddingModel 网关嵌入模型名，这里原样保留
     * @param timeout 调用超时；为 null 时改成 60 秒
     * @param grokApiKey Grok 密钥，这里原样保留；null 收成空串
     */
    public LlmSettings {
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
        if (grokApiKey == null) {
            grokApiKey = "";
        }
    }

    /**
     * 兼容还没有 Grok 密钥的调用。Grok 密钥按空串处理，DeepSeek 和网关字段保持不变。
     *
     * @param deepseekApiKey DeepSeek 的访问密钥
     * @param deepseekThinking 是否打开 DeepSeek 思考
     * @param gatewayBaseUrl 网关根地址
     * @param gatewayApiKey 网关密钥
     * @param gatewayModel 网关聊天模型名
     * @param gatewayEmbeddings 是否启用网关嵌入
     * @param gatewayEmbeddingModel 网关嵌入模型名
     * @param timeout 调用超时；为 null 时改成 60 秒
     */
    public LlmSettings(
            String deepseekApiKey,
            boolean deepseekThinking,
            String gatewayBaseUrl,
            String gatewayApiKey,
            String gatewayModel,
            boolean gatewayEmbeddings,
            String gatewayEmbeddingModel,
            Duration timeout) {
        this(deepseekApiKey, deepseekThinking, gatewayBaseUrl, gatewayApiKey, gatewayModel, gatewayEmbeddings, gatewayEmbeddingModel, timeout, "");
    }

    /**
     * 用当前进程的环境变量组装模型配置。
     *
     * @return 从进程环境读出的配置；没写超时秒数时超时为 60 秒
     */
    public static LlmSettings fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * 从给定的环境映射读取 DeepSeek、网关和超时。思考与嵌入开关只把 true 当成打开。
     *
     * @param env 环境变量名到值的映射，缺键时对应项为空或关闭
     * @return 按这些变量建成的配置
     */
    public static LlmSettings fromEnvironment(Map<String, String> env) {
        return new LlmSettings(
                env.get("JASAGENT_DEEPSEEK_API_KEY"),
                truthy(env.get("JASAGENT_DEEPSEEK_THINKING")),
                env.get("JASAGENT_GATEWAY_BASE_URL"),
                env.get("JASAGENT_GATEWAY_API_KEY"),
                env.get("JASAGENT_GATEWAY_MODEL"),
                truthy(env.get("JASAGENT_GATEWAY_EMBEDDINGS")),
                env.get("JASAGENT_GATEWAY_EMBEDDING_MODEL"),
                timeout(env.get("JASAGENT_LLM_TIMEOUT_SECONDS")),
                grokKey(env));
    }

    /**
     * 复制一份设置并改掉 DeepSeek 思考开关。Grok 密钥和网关字段保持原值。
     *
     * @param thinking 新的思考开关
     * @return 只改了思考开关的新设置
     */
    public LlmSettings withDeepseekThinking(boolean thinking) {
        return new LlmSettings(
                deepseekApiKey,
                thinking,
                gatewayBaseUrl,
                gatewayApiKey,
                gatewayModel,
                gatewayEmbeddings,
                gatewayEmbeddingModel,
                timeout,
                grokApiKey);
    }

    /**
     * Grok 密钥是否已配置。
     *
     * @return 密钥非空白时为 true
     */
    public boolean grokConfigured() {
        return !isBlank(grokApiKey);
    }

    /**
     * 判断网关根地址是否写了非空白内容。
     *
     * @return 根地址有内容时为 true
     */
    public boolean gatewayConfigured() {
        return !isBlank(gatewayBaseUrl);
    }

    /**
     * 判断配置开关是不是忽略大小写的 true。
     *
     * @param value 环境变量原文；null 视为关闭
     * @return 值等于 true 时为 true
     */
    private static boolean truthy(String value) {
        return value != null && value.equalsIgnoreCase("true");
    }

    /**
     * 把超时秒数换成等待时长。空白时用 60 秒。
     *
     * @param seconds 十进制秒数文本；空白表示用默认值
     * @return 对应的等待时长
     */
    private static Duration timeout(String seconds) {
        if (isBlank(seconds)) {
            return Duration.ofSeconds(60);
        }
        return Duration.ofSeconds(Long.parseLong(seconds.trim()));
    }

    /**
     * 判断配置文本是否缺失或只有空白。
     *
     * @param value 待检查的文本
     * @return null 或空白时为 true
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 按顺序取 Grok 密钥：进程里的 JASAGENT_GROK_API_KEY、XAI_API_KEY，然后是启动时放进进程的那一份。
     *
     * @param env 环境变量
     * @return 第一个非空白密钥；都没有时是空串
     */
    private static String grokKey(Map<String, String> env) {
        String named = env.get("JASAGENT_GROK_API_KEY");
        if (!isBlank(named)) {
            return named.trim();
        }
        String xai = env.get("XAI_API_KEY");
        if (!isBlank(xai)) {
            return xai.trim();
        }
        return GrokCredentials.current();
    }

    /**
     * 返回不含任何密钥的摘要，避免设置对象被日志打出时带上 DeepSeek、网关或 Grok 的密钥。
     *
     * @return 只含是否配置和超时的文本
     */
    @Override
    public String toString() {
        return "LlmSettings[deepseekConfigured=" + !isBlank(deepseekApiKey)
                + ", gatewayConfigured=" + gatewayConfigured()
                + ", grokConfigured=" + grokConfigured()
                + ", timeout=" + timeout + "]";
    }
}
