package dev.jasagent.llm;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HashingEmbedder {
    public static final int DIMENSION = 256;

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

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "无法计算嵌入", null, ex);
        }
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] combined = new byte[left.length + right.length];
        System.arraycopy(left, 0, combined, 0, left.length);
        System.arraycopy(right, 0, combined, left.length, right.length);
        return combined;
    }

    private static byte[] intBytes(int value) {
        return new byte[] {
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }
}
