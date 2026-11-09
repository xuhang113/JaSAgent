package dev.jasagent.rag;

/**
 * 不访问模型服务的嵌入器。
 * 每个词按哈希放进固定维度的一桶，并带上符号，最后把整条向量缩放到单位长度。
 */
public final class HashingEmbedder implements Embedder {
    /** 嵌入向量的桶数。词落在哪一维由哈希对 256 取模决定。 */
    public static final int DIMENSION = 256;

    /**
     * 把文本里的词累加进 256 维向量并做 L2 归一化。
     * 没有词时返回全零向量，不再做除法。
     *
     * @param text 查询或段正文
     * @return 长度为 256 的向量；有词时长度为 1
     */
    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSION];
        for (String token : Tokenizer.tokens(text)) {
            int hash = token.hashCode();
            int index = Math.floorMod(hash, DIMENSION);
            float sign = ((hash >>> 1) & 1) == 0 ? 1f : -1f;
            vector[index] += sign;
        }
        double norm = 0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        if (norm == 0) {
            return vector;
        }
        float scale = (float) Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= scale;
        }
        return vector;
    }
}
