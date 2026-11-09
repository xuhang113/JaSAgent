package dev.jasagent.api.llm;

/**
 * 对话应答可以归属的供应方。枚举名用于在 {@link ChatResponse} 里还原供应方种类。
 */
public enum LlmProvider {
    /** DeepSeek 直连，压缩、重排、评判以及网关不可用时的主循环都走这里。 */
    DEEPSEEK,
    /** AI Gateway。主循环在网关健康且调用方未点名其他供应方时优先使用。 */
    AI_GATEWAY
}
