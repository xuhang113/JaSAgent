package dev.jasagent.api.llm;

/**
 * 上下文压缩、评测裁判等调用方看到的一次对话补全结果。
 *
 * @param provider 实际应答的供应方名称，来自 {@link LlmProvider#name()} 或调用方直接传入的字符串
 * @param model 实际使用的模型名
 * @param message 模型返回的消息，含角色、正文和工具调用
 * @param usage 这次补全的提示词与生成 token 数
 * @param latencyMillis 从发起调用到拿到结果的毫秒数
 * @param fallback 为 true 时表示主路径失败后改由备用供应方完成
 */
public record ChatResponse(
        String provider,
        String model,
        ChatMessage message,
        TokenUsage usage,
        long latencyMillis,
        boolean fallback) {
    /**
     * 用供应方枚举构造补全结果，枚举名写入 provider 字符串；枚举为 null 时 provider 也是 null。
     *
     * @param provider 实际应答的供应方，null 表示调用方没有供应方枚举
     * @param model 实际使用的模型名
     * @param message 模型返回的消息
     * @param usage 这次补全的 token 用量
     * @param latencyMillis 从发起调用到拿到结果的毫秒数
     * @param fallback 为 true 时表示本次应答来自备用供应方
     */
    public ChatResponse(LlmProvider provider, String model, ChatMessage message, TokenUsage usage, long latencyMillis, boolean fallback) {
        this(provider == null ? null : provider.name(), model, message, usage, latencyMillis, fallback);
    }

    /**
     * 把 provider 字符串还原成供应方枚举。
     *
     * @return 能按 {@link LlmProvider} 枚举名解析时返回对应常量；provider 为空或对不上枚举名时返回 null
     */
    public LlmProvider providerKind() {
        if (provider == null) {
            return null;
        }
        try {
            return LlmProvider.valueOf(provider);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
