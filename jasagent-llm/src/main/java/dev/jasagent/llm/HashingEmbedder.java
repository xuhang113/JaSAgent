package dev.jasagent.llm;

public final class HashingEmbedder {
    private static final int DIMENSIONS = 64;

    private HashingEmbedder() {
    }

    public static float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }
        for (String token : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (token.isEmpty()) {
                continue;
            }
            int hash = token.hashCode();
            int index = Math.floorMod(hash, DIMENSIONS);
            vector[index] += (hash & 1) == 0 ? 1f : -1f;
        }
        float norm = 0f;
        for (float value : vector) {
            norm += value * value;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0f) {
            for (int index = 0; index < vector.length; index++) {
                vector[index] /= norm;
            }
        }
        return vector;
    }
}
