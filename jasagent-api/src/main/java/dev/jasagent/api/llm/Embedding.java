package dev.jasagent.api.llm;

public record Embedding(String embedder, float[] vector) {
}
