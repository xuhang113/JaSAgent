package dev.jasagent.rag;

public final class HashingEmbedder implements Embedder {
    public static final int DIMENSION = 256;

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
