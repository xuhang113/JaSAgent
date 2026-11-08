package dev.jasagent.api.llm;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public interface ModelClient {
    String provider();

    boolean available();

    boolean supportsEmbeddings();

    CompletionStage<ChatResponse> chat(String model, ChatRequest request, Duration timeout, boolean thinking);

    CompletionStage<float[]> embed(String text, String model, Duration timeout);
}
