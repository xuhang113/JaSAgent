package dev.jasagent.llm;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.GatewayHealth;
import dev.jasagent.api.llm.LlmCall;
import dev.jasagent.api.llm.LlmCallStore;
import dev.jasagent.api.llm.LlmChatRequest;
import dev.jasagent.api.llm.LlmChatResponse;
import dev.jasagent.api.llm.LlmClient;
import dev.jasagent.api.llm.LlmEmbedRequest;
import dev.jasagent.api.llm.LlmEmbedResponse;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.llm.LlmTrace;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public final class LlmRouter implements LlmClient {
    private final LlmSettings settings;
    private final GatewayHealth health;
    private final LlmClient deepseek;
    private final LlmClient gateway;
    private final LlmCallStore store;
    private final LlmTrace trace;
    private final HashingEmbedder hashing = new HashingEmbedder();

    public LlmRouter(LlmSettings settings, GatewayHealth health, LlmClient deepseek, LlmClient gateway, LlmCallStore store, LlmTrace trace) {
        this.settings = settings;
        this.health = health;
        this.deepseek = deepseek;
        this.gateway = gateway;
        this.store = store;
        this.trace = trace;
    }

    @Override
    public CompletionStage<LlmChatResponse> chat(LlmChatRequest request) {
        return VirtualIo.supply(() -> {
            try {
                return chatBlocking(request);
            } catch (LlmTimeoutException ex) {
                throw new AgentException(ErrorCode.LLM, AuthorizationRedactor.redact(ex.getMessage()), request.executionId(), ex);
            }
        });
    }

    @Override
    public CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request) {
        return VirtualIo.supply(() -> embedBlocking(request));
    }

    private LlmChatResponse chatBlocking(LlmChatRequest request) {
        if (!isBlank(request.model())) {
            return chatChosen(request);
        }
        if (!isBlank(request.provider())) {
            return chatPinned(request, request.provider());
        }
        if (request.task() == LlmTask.REACT && gatewayUsable()) {
            try {
                return invokeOnce(request, gateway, "ai-gateway", settings.gatewayModel(), false);
            } catch (RuntimeException ex) {
                if (!deepseekUsable()) {
                    throw fail(request, "deepseek 不可用", ex);
                }
                trace(request.executionId(), "fallback provider=deepseek model=deepseek-flash " + AuthorizationRedactor.redact(ex.getMessage()));
                return invokeWithTimeoutRetry(request, deepseek, "deepseek", OpenAiCallConfig.DEEPSEEK_MODEL, true);
            }
        }
        if (!deepseekUsable()) {
            throw fail(request, "deepseek 不可用", null);
        }
        return invokeWithTimeoutRetry(request, deepseek, "deepseek", OpenAiCallConfig.DEEPSEEK_MODEL, false);
    }

    private LlmChatResponse chatChosen(LlmChatRequest request) {
        String provider = isBlank(request.provider()) ? "deepseek" : request.provider();
        if ("deepseek".equals(provider)) {
            if (!deepseekUsable()) {
                throw fail(request, "deepseek 不可用", null);
            }
            return invokeWithTimeoutRetry(request, deepseek, "deepseek", request.model(), false);
        }
        if ("ai-gateway".equals(provider)) {
            if (!gatewayUsable()) {
                throw fail(request, "ai-gateway 不可用", null);
            }
            return invokeWithTimeoutRetry(request, gateway, "ai-gateway", request.model(), false);
        }
        throw fail(request, "不支持的供应方", null);
    }

    private LlmChatResponse chatPinned(LlmChatRequest request, String provider) {
        if ("deepseek".equals(provider)) {
            if (!deepseekUsable()) {
                throw fail(request, "deepseek 不可用", null);
            }
            return invokeWithTimeoutRetry(request, deepseek, "deepseek", OpenAiCallConfig.DEEPSEEK_MODEL, false);
        }
        if ("ai-gateway".equals(provider)) {
            if (!gatewayUsable()) {
                throw fail(request, "ai-gateway 不可用", null);
            }
            return invokeWithTimeoutRetry(request, gateway, "ai-gateway", settings.gatewayModel(), false);
        }
        throw fail(request, "不支持的供应方", null);
    }

    private LlmChatResponse invokeWithTimeoutRetry(LlmChatRequest request, LlmClient client, String provider, String model, boolean fallback) {
        try {
            return invokeOnce(request, client, provider, model, fallback);
        } catch (LlmTimeoutException ex) {
            if (request.task() == LlmTask.REACT && request.remainingSteps() >= 1) {
                return invokeOnce(request, client, provider, model, fallback);
            }
            throw ex;
        }
    }

    private LlmChatResponse invokeOnce(LlmChatRequest request, LlmClient client, String provider, String model, boolean fallback) {
        long start = System.nanoTime();
        LlmChatResponse raw = call(client, request);
        long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        String text = raw.text() == null ? "" : raw.text();
        String thinking = raw.thinking() == null ? "" : raw.thinking();
        LlmChatResponse response = new LlmChatResponse(
                provider,
                model,
                text,
                raw.promptTokens(),
                raw.completionTokens(),
                raw.totalTokens(),
                fallback,
                thinking);
        Stages.join(store.save(new LlmCall(
                request.executionId(),
                request.task() == null ? "" : request.task().wireName(),
                provider,
                model,
                latency,
                raw.promptTokens(),
                raw.completionTokens(),
                raw.totalTokens(),
                fallback)));
        return response;
    }

    private LlmChatResponse call(LlmClient client, LlmChatRequest request) {
        try {
            return Stages.join(client.chat(request));
        } catch (LlmTimeoutException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            if (LlmTimeoutException.matches(ex)) {
                throw new LlmTimeoutException(request.executionId(), "模型超时", ex);
            }
            if (ex instanceof AgentException agent) {
                throw agent;
            }
            throw fail(request, ex.getMessage(), ex);
        }
    }

    private LlmEmbedResponse embedBlocking(LlmEmbedRequest request) {
        if (embeddingsUsable()) {
            long start = System.nanoTime();
            LlmEmbedResponse raw = Stages.join(gateway.embed(request));
            long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            trace(request.executionId(), "embedder=ai-gateway model=" + settings.gatewayEmbeddingModel());
            saveEmbed(request, "ai-gateway", settings.gatewayEmbeddingModel(), latency);
            float[] vector = raw.vector() == null ? new float[0] : raw.vector();
            return new LlmEmbedResponse("ai-gateway", settings.gatewayEmbeddingModel(), vector);
        }
        long start = System.nanoTime();
        float[] vector = hashing.embed(request.text());
        long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        trace(request.executionId(), "embedder=hashing");
        saveEmbed(request, "hashing", "hashing", latency);
        return new LlmEmbedResponse("hashing", "hashing", vector);
    }

    private void saveEmbed(LlmEmbedRequest request, String provider, String model, long latency) {
        Stages.join(store.save(new LlmCall(request.executionId(), "embed", provider, model, latency, 0, 0, 0, false)));
    }

    private void trace(String executionId, String line) {
        Stages.join(trace.write(executionId, AuthorizationRedactor.redact(line)));
    }

    private boolean gatewayUsable() {
        return !isBlank(settings.gatewayBaseUrl())
                && !isBlank(settings.gatewayApiKey())
                && !isBlank(settings.gatewayModel())
                && health.healthy();
    }

    private boolean deepseekUsable() {
        return !isBlank(settings.deepseekApiKey());
    }

    private boolean embeddingsUsable() {
        return settings.gatewayEmbeddings()
                && !isBlank(settings.gatewayBaseUrl())
                && !isBlank(settings.gatewayApiKey())
                && !isBlank(settings.gatewayEmbeddingModel())
                && health.healthy();
    }

    private static AgentException fail(LlmChatRequest request, String message, Throwable cause) {
        return new AgentException(ErrorCode.LLM, AuthorizationRedactor.redact(message), request.executionId(), cause);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
