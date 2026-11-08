package dev.jasagent.llm;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatModel;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.Embedding;
import dev.jasagent.api.llm.LlmCallRecord;
import dev.jasagent.api.llm.LlmCallSink;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.llm.ModelClient;
import dev.jasagent.api.llm.RouteTrace;
import dev.jasagent.api.llm.TokenUsage;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public final class RoutingChatModel implements ChatModel {
    private final ModelClient deepseek;
    private final ModelClient gateway;
    private final boolean gatewayConfigured;
    private final boolean gatewayHealthy;
    private final String gatewayModel;
    private final boolean deepseekThinking;
    private final Duration timeout;
    private final RouteTrace trace;
    private final LlmCallSink sink;

    public RoutingChatModel(
            ModelClient deepseek,
            ModelClient gateway,
            boolean gatewayConfigured,
            boolean gatewayHealthy,
            String gatewayModel,
            boolean deepseekThinking,
            Duration timeout,
            RouteTrace trace,
            LlmCallSink sink) {
        this.deepseek = deepseek;
        this.gateway = gateway;
        this.gatewayConfigured = gatewayConfigured;
        this.gatewayHealthy = gatewayHealthy;
        this.gatewayModel = gatewayModel;
        this.deepseekThinking = deepseekThinking;
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.trace = trace;
        this.sink = sink;
    }

    @Override
    public CompletionStage<ChatResponse> complete(ChatRequest request) {
        Route route;
        try {
            route = choose(request);
        } catch (AgentException ex) {
            return CompletableFuture.failedFuture(ex);
        }
        CompletionStage<ChatResponse> primary = invoke(route, request, false);
        if (!fallbackAllowed(request, route)) {
            return primary;
        }
        return primary.handle((response, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(response);
            }
            if (trace != null) {
                trace.event("model.route", Map.of("fallback", "deepseek-flash"));
            }
            return invoke(deepseekRoute(), request, true);
        }).thenCompose(next -> next);
    }

    @Override
    public CompletionStage<Embedding> embed(String text, String executionId) {
        if (gatewayConfigured && gatewayHealthy && gateway != null && gateway.supportsEmbeddings()) {
            return gateway.embed(text, gatewayModel, timeout).thenApply(vector -> {
                if (trace != null) {
                    trace.event("embed", Map.of("embedder", "gateway"));
                }
                return new Embedding("gateway", vector);
            });
        }
        if (trace != null) {
            trace.event("embed", Map.of("embedder", "hashing"));
        }
        return CompletableFuture.completedFuture(new Embedding("hashing", HashingEmbedder.embed(text)));
    }

    private Route choose(ChatRequest request) {
        String named = request.namedProvider();
        if (named != null && !named.isBlank()) {
            if ("deepseek".equals(named)) {
                return deepseekRoute();
            }
            if ("ai-gateway".equals(named)) {
                return gatewayRoute();
            }
            throw new AgentException(ErrorCode.LLM, "不支持的供应方", request.executionId(), null);
        }
        if (request.task() == LlmTask.REACT && gatewayReady()) {
            return new Route(gateway, gatewayModel, "ai-gateway");
        }
        return deepseekRoute();
    }

    private boolean gatewayReady() {
        return gatewayConfigured
                && gatewayHealthy
                && gateway != null
                && gateway.available()
                && gatewayModel != null
                && !gatewayModel.isBlank();
    }

    private Route deepseekRoute() {
        if (deepseek == null || !deepseek.available()) {
            throw new AgentException(ErrorCode.LLM, "deepseek 不可用");
        }
        return new Route(deepseek, "deepseek-flash", "deepseek");
    }

    private Route gatewayRoute() {
        if (!gatewayReady()) {
            throw new AgentException(ErrorCode.LLM, "ai-gateway 不可用");
        }
        return new Route(gateway, gatewayModel, "ai-gateway");
    }

    private boolean fallbackAllowed(ChatRequest request, Route route) {
        return request.task() == LlmTask.REACT
                && "ai-gateway".equals(route.providerName())
                && (request.namedProvider() == null || request.namedProvider().isBlank());
    }

    private CompletionStage<ChatResponse> invoke(Route route, ChatRequest request, boolean fallback) {
        long started = System.nanoTime();
        boolean thinking = "deepseek".equals(route.providerName()) && deepseekThinking;
        return route.client().chat(route.model(), request, timeout, thinking).thenCompose(raw -> {
            long latency = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            TokenUsage usage = raw.usage() == null ? new TokenUsage(0, 0) : raw.usage();
            ChatResponse response = new ChatResponse(route.providerName(), route.model(), raw.message(), usage, latency, fallback);
            LlmCallRecord record = new LlmCallRecord(
                    request.executionId(),
                    response.provider(),
                    response.model(),
                    response.latencyMillis(),
                    usage.promptTokens(),
                    usage.completionTokens(),
                    request.task().name());
            CompletionStage<Void> recorded = sink == null
                    ? CompletableFuture.completedFuture(null)
                    : sink.record(record);
            return recorded.thenApply(ignored -> response);
        });
    }

    private record Route(ModelClient client, String model, String providerName) {
    }
}
