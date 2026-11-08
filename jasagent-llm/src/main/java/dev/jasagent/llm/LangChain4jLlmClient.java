package dev.jasagent.llm;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.LlmChatRequest;
import dev.jasagent.api.llm.LlmChatResponse;
import dev.jasagent.api.llm.LlmClient;
import dev.jasagent.api.llm.LlmEmbedRequest;
import dev.jasagent.api.llm.LlmEmbedResponse;
import dev.jasagent.api.llm.LlmMessage;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

public final class LangChain4jLlmClient implements LlmClient {
    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;

    private LangChain4jLlmClient(ChatModel chatModel, EmbeddingModel embeddingModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    public static LangChain4jLlmClient deepSeek(LlmSettings settings) {
        return new LangChain4jLlmClient(LangChain4jModels.chat(OpenAiCallConfig.deepSeek(settings)), null);
    }

    public static LangChain4jLlmClient gateway(LlmSettings settings) {
        EmbeddingModel embedding = null;
        if (settings.gatewayEmbeddings() && !isBlank(settings.gatewayEmbeddingModel())) {
            embedding = LangChain4jModels.embedding(OpenAiCallConfig.gatewayEmbed(settings));
        }
        return new LangChain4jLlmClient(LangChain4jModels.chat(OpenAiCallConfig.gatewayChat(settings)), embedding);
    }

    @Override
    public CompletionStage<LlmChatResponse> chat(LlmChatRequest request) {
        return VirtualIo.supply(() -> invokeChat(request));
    }

    @Override
    public CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request) {
        return VirtualIo.supply(() -> invokeEmbed(request));
    }

    private LlmChatResponse invokeChat(LlmChatRequest request) {
        if (chatModel == null) {
            throw new AgentException(ErrorCode.LLM, "没有聊天模型", request.executionId(), null);
        }
        try {
            ChatResponse response = chatModel.chat(toRequest(request));
            String text = "";
            if (response.aiMessage() != null && response.aiMessage().text() != null) {
                text = response.aiMessage().text();
            }
            TokenUsage usage = response.tokenUsage();
            int prompt = count(usage == null ? null : usage.inputTokenCount());
            int completion = count(usage == null ? null : usage.outputTokenCount());
            int total = usage == null || usage.totalTokenCount() == null ? prompt + completion : usage.totalTokenCount();
            return new LlmChatResponse("", "", text, prompt, completion, total, false);
        } catch (RuntimeException ex) {
            throw failure(request.executionId(), ex);
        }
    }

    private LlmEmbedResponse invokeEmbed(LlmEmbedRequest request) {
        if (embeddingModel == null) {
            throw new AgentException(ErrorCode.LLM, "embed 不走 DeepSeek", request.executionId(), null);
        }
        try {
            Response<Embedding> response = embeddingModel.embed(request.text() == null ? "" : request.text());
            return new LlmEmbedResponse("ai-gateway", "", response.content().vector());
        } catch (RuntimeException ex) {
            throw failure(request.executionId(), ex);
        }
    }

    private static ChatRequest toRequest(LlmChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        if (request.messages() != null) {
            for (LlmMessage message : request.messages()) {
                messages.add(toMessage(message, request.executionId()));
            }
        }
        return ChatRequest.builder().messages(messages).build();
    }

    private static ChatMessage toMessage(LlmMessage message, String executionId) {
        String role = message.role() == null ? "" : message.role();
        String text = message.text() == null ? "" : message.text();
        return switch (role) {
            case "system" -> SystemMessage.from(text);
            case "user" -> UserMessage.from(text);
            case "assistant" -> AiMessage.from(text);
            default -> throw new AgentException(ErrorCode.LLM, "不支持的消息角色", executionId, null);
        };
    }

    private static RuntimeException failure(String executionId, RuntimeException ex) {
        if (ex instanceof AgentException agent) {
            return agent;
        }
        if (LlmTimeoutException.matches(ex)) {
            return new LlmTimeoutException(executionId, "模型超时", ex);
        }
        return new AgentException(ErrorCode.LLM, AuthorizationRedactor.redact(ex.getMessage()), executionId, ex);
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
