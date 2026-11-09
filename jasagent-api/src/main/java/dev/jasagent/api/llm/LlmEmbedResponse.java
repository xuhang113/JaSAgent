package dev.jasagent.api.llm;

/**
 * 一次文本嵌入的返回值，说明向量来自哪个嵌入器和模型。
 *
 * @param embedder 实际产出向量的嵌入器，例如 ai-gateway 或 hashing
 * @param model 嵌入模型名；本地哈希嵌入时为 hashing
 * @param vector 文本对应的向量；缺失时调用方按空数组处理
 */
public record LlmEmbedResponse(String embedder, String model, float[] vector) {
}
