package dev.jasagent.app;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.LlmChatRequest;
import dev.jasagent.api.llm.LlmClient;
import dev.jasagent.api.llm.LlmEmbedRequest;
import dev.jasagent.api.llm.LlmEmbedResponse;
import dev.jasagent.api.llm.LlmMessage;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.llm.LangChain4jLlmClient;
import dev.jasagent.llm.LlmRouter;
import dev.jasagent.llm.LlmSettings;
import dev.jasagent.workflow.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class WorkflowModels {
    private WorkflowModels() {
    }

    static boolean configured() {
        LlmSettings settings = LlmSettings.fromEnvironment();
        return present(settings.deepseekApiKey())
                || (present(settings.gatewayBaseUrl()) && present(settings.gatewayApiKey()) && present(settings.gatewayModel()));
    }

    static ChatModel chat() {
        return chat(Map.of());
    }

    static ChatModel chat(Map<String, String> configuration) {
        if (!configured()) {
            return request -> CompletableFuture.failedFuture(
                    new AgentException(ErrorCode.LLM, "模型未配置", request.executionId(), null));
        }
        LlmSettings settings = applyThinking(LlmSettings.fromEnvironment(), configuration);
        LlmClient deepseek = present(settings.deepseekApiKey())
                ? LangChain4jLlmClient.deepSeek(settings)
                : new UnavailableClient();
        LlmClient gateway = present(settings.gatewayBaseUrl()) && present(settings.gatewayApiKey())
                ? LangChain4jLlmClient.gateway(settings)
                : new UnavailableClient();
        LlmRouter router = new LlmRouter(
                settings,
                () -> true,
                deepseek,
                gateway,
                call -> CompletableFuture.completedFuture(null),
                (executionId, line) -> CompletableFuture.completedFuture(null));
        return request -> router.chat(new LlmChatRequest(
                        request.executionId(),
                        LlmTask.REACT,
                        null,
                        messages(request.messages()),
                        1))
                .thenApply(response -> new ChatModel.ModelReply(
                        response.text() == null ? "" : response.text(),
                        List.of(),
                        response.thinking() == null ? "" : response.thinking()));
    }

    static LlmSettings applyThinking(LlmSettings settings, Map<String, String> configuration) {
        if (settings.deepseekThinking() || configuration == null || !truthy(configuration.get("jasagent.deepseek.thinking"))) {
            return settings;
        }
        return new LlmSettings(
                settings.deepseekApiKey(),
                true,
                settings.gatewayBaseUrl(),
                settings.gatewayApiKey(),
                settings.gatewayModel(),
                settings.gatewayEmbeddings(),
                settings.gatewayEmbeddingModel(),
                settings.timeout());
    }

    private static boolean truthy(String value) {
        return value != null && value.equalsIgnoreCase("true");
    }

    private static List<LlmMessage> messages(List<ChatModel.ChatMessage> messages) {
        List<LlmMessage> converted = new ArrayList<>();
        if (messages == null) {
            return converted;
        }
        for (ChatModel.ChatMessage message : messages) {
            converted.add(new LlmMessage(message.role() == null ? "" : message.role(), message.content() == null ? "" : message.content()));
        }
        return converted;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static final class UnavailableClient implements LlmClient {
        @Override
        public CompletionStage<dev.jasagent.api.llm.LlmChatResponse> chat(LlmChatRequest request) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "模型未配置", request.executionId(), null));
        }

        @Override
        public CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "模型未配置", request.executionId(), null));
        }
    }
}
