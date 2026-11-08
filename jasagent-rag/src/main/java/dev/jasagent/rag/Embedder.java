package dev.jasagent.rag;

public interface Embedder {
    float[] embed(String text);
}
