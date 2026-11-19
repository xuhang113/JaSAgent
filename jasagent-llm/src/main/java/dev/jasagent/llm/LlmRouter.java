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

/**
 * 按任务、指定供应方和网关健康状况在 DeepSeek 与网关之间选择，并记下调用和追踪。
 */
public final class LlmRouter implements LlmClient {
    private final LlmSettings settings;
    private final GatewayHealth health;
    private final LlmClient deepseek;
    private final LlmClient gateway;
    private final LlmClient grok;
    private final LlmCallStore store;
    private final LlmTrace trace;
    private final HashingEmbedder hashing = new HashingEmbedder();

    /**
     * 接上配置、健康检查、两条模型客户端、调用记录和追踪。本地哈希嵌入器在这里自己创建。
     *
     * @param settings DeepSeek、网关和超时配置
     * @param health 网关当前是否健康
     * @param deepseek DeepSeek 聊天客户端
     * @param gateway 网关聊天和嵌入客户端
     * @param store 保存每次模型调用的记录
     * @param trace 写入执行追踪的出口
     */
    public LlmRouter(LlmSettings settings, GatewayHealth health, LlmClient deepseek, LlmClient gateway, LlmCallStore store, LlmTrace trace) {
        this(settings, health, deepseek, gateway, null, store, trace);
    }

    /**
     * 接上配置、健康检查、DeepSeek、网关、Grok、调用记录和追踪。
     * 自动路由仍然只在 DeepSeek 和网关之间选择。Grok 只在调用方点名 grok 或模型 grok-4.7 时使用。
     *
     * @param settings DeepSeek、网关、Grok 和超时配置
     * @param health 网关当前是否健康
     * @param deepseek DeepSeek 聊天客户端
     * @param gateway 网关聊天和嵌入客户端
     * @param grok Grok 聊天客户端；没有配置时为 null
     * @param store 保存每次模型调用的记录
     * @param trace 写入执行追踪的出口
     */
    public LlmRouter(LlmSettings settings, GatewayHealth health, LlmClient deepseek, LlmClient gateway, LlmClient grok, LlmCallStore store, LlmTrace trace) {
        this.settings = settings;
        this.health = health;
        this.deepseek = deepseek;
        this.gateway = gateway;
        this.grok = grok;
        this.store = store;
        this.trace = trace;
    }

    /**
     * 在虚拟线程上完成一次聊天路由。进入虚拟线程前先抄下租户、用户和会话，避免新线程看不见调用方的身份。
     * 超时收成带执行编号、且说明已脱敏的模型错误。
     *
     * @param request 含任务、供应方、消息和剩余步数的聊天请求
     * @return 完成后给出实际供应方和模型名的聊天结果
     */
    @Override
    public CompletionStage<LlmChatResponse> chat(LlmChatRequest request) {
        CallerCarry.Snapshot caller = CallerCarry.capture();
        return VirtualIo.supply(() -> caller.call(() -> {
            try {
                return chatBlocking(request);
            } catch (LlmTimeoutException ex) {
                throw new AgentException(ErrorCode.LLM, AuthorizationRedactor.redact(ex.getMessage()), request.executionId(), ex);
            }
        }));
    }

    /**
     * 在虚拟线程上完成一次嵌入。进入虚拟线程前先抄下租户、用户和会话。
     * 网关嵌入可用时走网关，否则用本地哈希向量。
     *
     * @param request 含执行编号和待嵌入文本的请求
     * @return 完成后给出来源名称和向量的阶段
     */
    @Override
    public CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request) {
        CallerCarry.Snapshot caller = CallerCarry.capture();
        return VirtualIo.supply(() -> caller.call(() -> embedBlocking(request)));
    }

    /**
     * 按请求选择聊天路径。写了模型名就按模型走，只钉了供应方就按供应方走。REACT 且网关可用时先试网关，失败再退回 DeepSeek；其他任务在 DeepSeek 可用时直接走 DeepSeek。
     *
     * @param request 聊天请求
     * @return 已写上实际供应方、模型名和是否回退的响应
     */
    private LlmChatResponse chatBlocking(LlmChatRequest request) {
        if (grokRequested(request)) {
            return chatGrok(request);
        }
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

    /**
     * 调用方点名 grok，或没点供应方但模型是 grok-4.7 时走 Grok。点名 deepseek 时不因为模型名改道。
     *
     * @param request 聊天请求
     * @return 应该使用 Grok 时为 true
     */
    private static boolean grokRequested(LlmChatRequest request) {
        if ("grok".equals(request.provider())) {
            return true;
        }
        return isBlank(request.provider()) && OpenAiCallConfig.GROK_MODEL.equals(request.model());
    }

    /**
     * 只调用 Grok。密钥或客户端缺失时失败，不改走 DeepSeek 或网关。
     *
     * @param request 点名了 Grok 的聊天请求
     * @return Grok 的聊天结果
     */
    private LlmChatResponse chatGrok(LlmChatRequest request) {
        if (grok == null || settings == null || !settings.grokConfigured()) {
            throw fail(request, "grok 不可用", null);
        }
        String model = isBlank(request.model()) ? OpenAiCallConfig.GROK_MODEL : request.model();
        return invokeWithTimeoutRetry(request, grok, "grok", model, false);
    }

    /**
     * 按请求里的模型名调用对应供应方。供应方空白时当作 DeepSeek。不认识的供应方直接失败，不改走另一边。
     *
     * @param request 已经写了模型名的聊天请求
     * @return 该模型的聊天结果
     */
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

    /**
     * 按钉住的供应方调用它的默认模型。该供应方不可用时失败，不自动换到另一边。
     *
     * @param request 聊天请求
     * @param provider 钉住的供应方，取 deepseek 或 ai-gateway
     * @return 该供应方默认模型的聊天结果
     */
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

    /**
     * 调用一次所选客户端。REACT 且剩余步数至少为 1 时，超时后对同一客户端再试一次。
     *
     * @param request 聊天请求
     * @param client 实际发起调用的客户端
     * @param provider 要写进结果的供应方名
     * @param model 要写进结果的模型名
     * @param fallback 这次是不是从网关退回 DeepSeek
     * @return 成功的聊天结果；不能再重试的超时继续抛出
     */
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

    /**
     * 调用客户端一次，记下耗时和 token，把供应方、模型名和是否回退写进响应，并保存一条调用记录。
     *
     * @param request 聊天请求
     * @param client 实际发起调用的客户端
     * @param provider 供应方名
     * @param model 模型名
     * @param fallback 是否属于回退调用
     * @return 补齐供应方信息后的响应
     */
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
                thinking,
                raw.toolCalls());
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

    /**
     * 等待客户端的聊天结果。超时收成模型超时，已是业务异常的原样抛出，其余收成模型错误。
     *
     * @param client 被调用的客户端
     * @param request 原聊天请求，超时时用来取执行编号
     * @return 客户端返回的原始聊天结果
     */
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

    /**
     * 网关嵌入可用时走网关并记追踪，否则用哈希嵌入器。两条路径都保存一条嵌入调用记录。
     *
     * @param request 嵌入请求
     * @return 带来源和向量的结果
     */
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

    /**
     * 保存一条嵌入调用记录。token 记 0，并且不标成回退。
     *
     * @param request 嵌入请求，提供执行编号
     * @param provider 实际使用的嵌入来源
     * @param model 嵌入模型名；哈希路径使用 hashing
     * @param latency 这次嵌入花费的毫秒数
     */
    private void saveEmbed(LlmEmbedRequest request, String provider, String model, long latency) {
        Stages.join(store.save(new LlmCall(request.executionId(), "embed", provider, model, latency, 0, 0, 0, false)));
    }

    /**
     * 写一行已经脱敏的执行追踪。
     *
     * @param executionId 这次执行的编号
     * @param line 要写入的追踪文本，写入前会去掉授权信息
     */
    private void trace(String executionId, String line) {
        Stages.join(trace.write(executionId, AuthorizationRedactor.redact(line)));
    }

    /**
     * 判断网关能不能用来聊天：地址、密钥和聊天模型都有，并且健康检查通过。
     *
     * @return 可以拿网关做聊天时为 true
     */
    private boolean gatewayUsable() {
        return !isBlank(settings.gatewayBaseUrl())
                && !isBlank(settings.gatewayApiKey())
                && !isBlank(settings.gatewayModel())
                && health.healthy();
    }

    /**
     * 判断 DeepSeek 密钥是否已配置。
     *
     * @return 密钥非空白时为 true
     */
    private boolean deepseekUsable() {
        return !isBlank(settings.deepseekApiKey());
    }

    /**
     * 判断能不能走网关做嵌入：开关打开，地址、密钥和嵌入模型都有，并且健康检查通过。
     *
     * @return 可以走网关嵌入时为 true
     */
    private boolean embeddingsUsable() {
        return settings.gatewayEmbeddings()
                && !isBlank(settings.gatewayBaseUrl())
                && !isBlank(settings.gatewayApiKey())
                && !isBlank(settings.gatewayEmbeddingModel())
                && health.healthy();
    }

    /**
     * 用脱敏后的说明建一条模型错误，执行编号取自这次聊天请求。
     *
     * @param request 失败所属的聊天请求
     * @param message 失败说明，会先去掉授权信息
     * @param cause 底层原因，可以为 null
     * @return 带模型错误码的业务异常
     */
    private AgentException fail(LlmChatRequest request, String message, Throwable cause) {
        String secret = settings == null ? "" : settings.grokApiKey();
        Throwable safeCause = cause;
        if (safeCause != null && secret != null && secret.length() >= 8 && String.valueOf(safeCause).contains(secret)) {
            safeCause = null;
        }
        return new AgentException(ErrorCode.LLM, AuthorizationRedactor.redactSecret(message, secret), request.executionId(), safeCause);
    }

    /**
     * 判断文本是否缺失或只有空白。
     *
     * @param value 待检查的文本
     * @return null 或空白时为 true
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
