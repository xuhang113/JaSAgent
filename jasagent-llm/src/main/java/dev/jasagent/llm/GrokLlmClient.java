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
import dev.jasagent.llm.egress.SidecarLlmTransportBuilder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Grok 的模型客户端。先用 LangChain4j 的 OpenAI 兼容聊天补全客户端访问 /chat/completions。
 * 只有该接口对 grok-4.7 不可用时，才改用同一库的 responses 客户端访问 /v1/responses。
 * 不自写 HTTP，也不打开请求日志。
 */
public final class GrokLlmClient implements LlmClient {
    private final LlmClient completions;
    private final ChatModel responses;
    private final String apiKey;
    /**
     * 一旦确认聊天补全不可用，后续调用直接走 responses，避免每次都先打一次会失败的接口。
     */
    private final AtomicBoolean responsesOnly = new AtomicBoolean(false);

    /**
     * 记下聊天补全客户端、responses 客户端和用于脱敏的密钥。
     *
     * @param completions 走 /chat/completions 的 LangChain4j 客户端
     * @param responses 走 /v1/responses 的 LangChain4j 客户端
     * @param apiKey 只用于从异常文本里抹掉密钥，不写入日志
     */
    private GrokLlmClient(LlmClient completions, ChatModel responses, String apiKey) {
        this.completions = completions;
        this.responses = responses;
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    /**
     * 按 Grok 配置同时建聊天补全客户端和 responses 客户端。两者都关闭请求日志和响应日志，并经 sidecar 访问离开集群的地址。
     *
     * @param settings 含 Grok 密钥和超时的配置
     * @return 可按可用性在两条接口之间选择的客户端
     */
    public static GrokLlmClient create(LlmSettings settings) {
        OpenAiCallConfig config = OpenAiCallConfig.grok(settings);
        OpenAiResponsesChatModel responses = OpenAiResponsesChatModel.builder()
                .httpClientBuilder(new SidecarLlmTransportBuilder())
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .logRequests(false)
                .logResponses(false)
                .build();
        return new GrokLlmClient(LangChain4jLlmClient.from(config), responses, config.apiKey());
    }

    /**
     * 在虚拟线程上调用 Grok。聊天补全不可用时只为这个供应方改走 responses。
     *
     * @param request 聊天请求
     * @return 完成后的聊天结果，供应方名留给路由填写
     */
    @Override
    public CompletionStage<LlmChatResponse> chat(LlmChatRequest request) {
        return VirtualIo.supply(() -> invoke(request));
    }

    /**
     * Grok 不提供本模块的嵌入。嵌入仍按原路由走网关或本地哈希。
     *
     * @param request 嵌入请求
     * @return 不会成功；阶段以模型错误失败
     */
    @Override
    public CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request) {
        return VirtualIo.supply(() -> {
            throw new AgentException(ErrorCode.LLM, "embed 不走 grok", request.executionId(), null);
        });
    }

    /**
     * 先尝试聊天补全。确认该接口不可用后改走 responses，并把异常说明里的密钥去掉。
     *
     * @param request 聊天请求
     * @return 聊天结果
     */
    private LlmChatResponse invoke(LlmChatRequest request) {
        if (responsesOnly.get()) {
            return fromResponses(request);
        }
        try {
            return Stages.join(completions.chat(request));
        } catch (RuntimeException ex) {
            if (!needsResponses(ex)) {
                throw scrub(request.executionId(), ex);
            }
            responsesOnly.set(true);
            return fromResponses(request);
        }
    }

    /**
     * 用 responses 客户端完成一次对话，并把 token 用量抄进结果。
     *
     * @param request 聊天请求
     * @return 不含供应方名的聊天结果
     */
    private LlmChatResponse fromResponses(LlmChatRequest request) {
        try {
            ChatResponse response = responses.chat(ChatRequest.builder().messages(messages(request)).build());
            String text = "";
            if (response != null && response.aiMessage() != null && response.aiMessage().text() != null) {
                text = response.aiMessage().text();
            }
            TokenUsage usage = response == null ? null : response.tokenUsage();
            int prompt = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
            int completion = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
            int total = usage == null || usage.totalTokenCount() == null ? prompt + completion : usage.totalTokenCount();
            return new LlmChatResponse("", OpenAiCallConfig.GROK_MODEL, text, prompt, completion, total, false);
        } catch (RuntimeException ex) {
            throw scrub(request.executionId(), ex);
        }
    }

    /**
     * 把本项目的消息换成 LangChain4j 消息。工具角色并进用户消息，避免 responses 路径丢掉上文。
     *
     * @param request 聊天请求
     * @return 按原顺序保留的消息
     */
    private static List<ChatMessage> messages(LlmChatRequest request) {
        List<ChatMessage> converted = new ArrayList<>();
        if (request.messages() == null) {
            return converted;
        }
        for (LlmMessage message : request.messages()) {
            String role = message.role() == null ? "" : message.role();
            String text = message.text() == null ? "" : message.text();
            if ("system".equals(role)) {
                converted.add(SystemMessage.from(text));
            } else if ("assistant".equals(role)) {
                converted.add(AiMessage.from(text));
            } else {
                converted.add(UserMessage.from(text));
            }
        }
        return converted;
    }

    /**
     * 判断这次失败是不是聊天补全接口本身不可用。认证失败和超时不改道。
     *
     * @param error 聊天补全抛出的异常
     * @return 应改走 responses 时为 true
     */
    static boolean needsResponses(Throwable error) {
        Throwable current = error;
        while (current != null) {
            int status = statusCode(current);
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (status == 401 || status == 403 || message.contains("unauthorized") || message.contains("invalid api key")) {
                return false;
            }
            if (status == 404 || status == 405 || status == 410) {
                return true;
            }
            if (message.contains("not supported") || message.contains("unsupported")
                    || message.contains("/v1/responses") || message.contains("responses api")) {
                return true;
            }
            if (status == 400 && message.contains("chat/completions")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 读取异常上的 HTTP 状态码。没有这个方法时返回 0。
     *
     * @param error 异常
     * @return 状态码；读不到时为 0
     */
    private static int statusCode(Throwable error) {
        try {
            Object value = error.getClass().getMethod("statusCode").invoke(error);
            if (value instanceof Integer status) {
                return status;
            }
        } catch (Exception ignored) {
            // 不是带状态码的 HTTP 异常。
        }
        return 0;
    }

    /**
     * 把异常收成不含密钥的模型错误。不把原始异常挂到 cause 上，避免栈里留下密钥。
     *
     * @param executionId 执行编号
     * @param error 原始失败
     * @return 已脱敏的模型异常
     */
    private AgentException scrub(String executionId, RuntimeException error) {
        String message = error.getMessage() == null ? "grok 调用失败" : error.getMessage();
        return new AgentException(ErrorCode.LLM, AuthorizationRedactor.redactSecret(message, apiKey), executionId, null);
    }
}
