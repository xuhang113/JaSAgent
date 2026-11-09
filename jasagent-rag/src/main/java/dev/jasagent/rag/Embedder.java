package dev.jasagent.rag;

/**
 * 把一段文本变成向量，供向量路计算和查询的余弦相似度。
 */
public interface Embedder {
    /**
     * 嵌入一段查询或正文。
     *
     * @param text 要嵌入的文本
     * @return 与实现约定维度一致的向量
     */
    float[] embed(String text);
}
