package dev.jasagent.api.llm;

import java.util.concurrent.CompletionStage;

public interface LlmClient {
    CompletionStage<LlmChatResponse> chat(LlmChatRequest request);

    CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request);
}
