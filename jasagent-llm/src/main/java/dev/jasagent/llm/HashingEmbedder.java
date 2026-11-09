package dev.jasagent.llm;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 用 SHA-256 把文本摊成固定 256 维单位向量，在网关嵌入不可用时充当本地嵌入器。
 */
public final class HashingEmbedder {
    /**
     * 生成向量的维数。同一段文本每次都会得到同样长度的结果。
     */
    public static final int DIMENSION = 256;

    /**
     * 把一段文本变成单位长度的 256 维向量。相同文本得到相同向量，空文本按空串处理。
     *
     * @param text 要嵌入的原文；null 按空串计算
     * @return 已归一化的 256 维向量
     */
    public float[] embed(String text) {
        byte[] seed = sha256((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            byte[] block = sha256(concat(seed, intBytes(i)));
            int bits = ((block[0] & 0xff) << 24)
                    | ((block[1] & 0xff) << 16)
                    | ((block[2] & 0xff) << 8)
                    | (block[3] & 0xff);
            vector[i] = (bits & 0xffff) / 32768.0f - 1.0f;
        }
        normalize(vector);
        return vector;
    }

    /**
     * 把向量就地缩放到单位长度。全零向量保持原样，避免除以零。
     *
     * @param vector 待归一化的向量，方法会改写其中每个分量
     */
    private static void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        if (sum == 0.0d) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }

    /**
     * 计算输入的 SHA-256 摘要。运行环境缺少该算法时抛出内部错误。
     *
     * @param input 要摘要的字节
     * @return 32 字节的 SHA-256 摘要
     */
    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "无法计算嵌入", null, ex);
        }
    }

    /**
     * 把两段字节按左后右的顺序拼成一段新数组。
     *
     * @param left 放在前面的字节
     * @param right 接在后面的字节
     * @return 拼接后的新数组，不改动两段入参
     */
    private static byte[] concat(byte[] left, byte[] right) {
        byte[] combined = new byte[left.length + right.length];
        System.arraycopy(left, 0, combined, 0, left.length);
        System.arraycopy(right, 0, combined, left.length, right.length);
        return combined;
    }

    /**
     * 把整数按大端序拆成 4 个字节，用作每一维的序号种子。
     *
     * @param value 维序号
     * @return 4 字节的大端表示
     */
    private static byte[] intBytes(int value) {
        return new byte[] {
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }
}
