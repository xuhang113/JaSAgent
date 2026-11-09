package dev.jasagent.llm;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

public final class LangChain4jModels {
    private LangChain4jModels() {
    }

    public static OpenAiChatModel chat(OpenAiCallConfig config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .timeout(config.timeout())
                .maxRetries(config.maxRetries())
                .logRequests(config.logRequests())
                .logResponses(config.logResponses())
                .customParameters(config.customParameters())
                .returnThinking(config.returnThinking())
                .sendThinking(false)
                .build();
    }

    public static OpenAiStreamingChatModel streaming(OpenAiCallConfig config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .timeout(config.timeout())
                .logRequests(config.logRequests())
                .logResponses(config.logResponses())
                .customParameters(config.customParameters())
                .returnThinking(config.returnThinking())
                .sendThinking(false)
                .build();
    }

    public static OpenAiEmbeddingModel embedding(OpenAiCallConfig config) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .timeout(config.timeout())
                .maxRetries(config.maxRetries())
                .logRequests(config.logRequests())
                .logResponses(config.logResponses())
                .build();
    }
}
