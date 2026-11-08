package dev.jasagent.api.llm;

public record LlmEmbedResponse(String embedder, String model, float[] vector) {
}
