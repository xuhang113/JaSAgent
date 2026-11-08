package dev.jasagent.api.llm;

import java.util.concurrent.CompletionStage;

public interface LlmClient {
    LlmProvider provider();

    CompletionStage<ChatResponse> chat(ChatRequest request);

    CompletionStage<Embedding> embed(String text, String executionId);
}
