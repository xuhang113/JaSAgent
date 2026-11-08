package dev.jasagent.api.llm;

import java.util.concurrent.CompletionStage;

public interface ChatModel {
    CompletionStage<ChatResponse> complete(ChatRequest request);

    CompletionStage<Embedding> embed(String text, String executionId);
}
