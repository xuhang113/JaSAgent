package dev.jasagent.llm;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.ModelClient;
import dev.jasagent.api.llm.TokenUsage;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class FakeModel implements ModelClient {
    final String provider;
    int chats;
    int embeds;
    boolean available = true;
    boolean embeddings;
    AgentException failure;

    FakeModel(String provider) {
        this.provider = provider;
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public boolean supportsEmbeddings() {
        return embeddings;
    }

    @Override
    public CompletionStage<ChatResponse> chat(String model, ChatRequest request, Duration timeout, boolean thinking) {
        chats++;
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        return CompletableFuture.completedFuture(new ChatResponse(
                provider,
                model,
                ChatMessage.text("assistant", "ok"),
                new TokenUsage(2, 3),
                1,
                false));
    }

    @Override
    public CompletionStage<float[]> embed(String text, String model, Duration timeout) {
        embeds++;
        if ("deepseek".equals(provider)) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "embed 不走 DeepSeek"));
        }
        return CompletableFuture.completedFuture(new float[] {1f});
    }
}
