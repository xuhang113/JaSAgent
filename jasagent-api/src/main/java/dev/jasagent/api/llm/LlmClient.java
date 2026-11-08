package dev.jasagent.api.llm;

import java.util.concurrent.CompletionStage;

public interface LlmClient {
    CompletionStage<LlmChatResponse> chat(LlmChatRequest request);

    CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request);

    default LlmProvider provider() {
        return null;
    }

    default CompletionStage<ChatResponse> chat(ChatRequest request) {
        throw new UnsupportedOperationException("chat(ChatRequest)");
    }

    default CompletionStage<Embedding> embed(String text, String executionId) {
        throw new UnsupportedOperationException("embed(text)");
    }
}
