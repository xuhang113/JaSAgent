package dev.jasagent.api.llm;

/**
 * 一段文本的嵌入结果，标明向量由哪个嵌入器产出。
 *
 * @param embedder 产出向量的嵌入器名称
 * @param vector 文本对应的向量
 */
public record Embedding(String embedder, float[] vector) {
}
