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
import dev.jasagent.api.llm.LlmToolCall;
import dev.jasagent.api.llm.ToolDefinition;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * 用 LangChain4j 调用 OpenAI 兼容接口，完成同步聊天、流式思考和文本嵌入。
 */
public final class LangChain4jLlmClient implements LlmClient {
    /**
     * 把工具参数的 JSON Schema 解析成对象结构时使用的映射器。
     */
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ChatModel chatModel;
    private final StreamingChatModel streamingModel;
    private final EmbeddingModel embeddingModel;

    /**
     * 保存这一路客户端要用的同步聊天、流式聊天和嵌入模型。嵌入模型可以空着。
     *
     * @param chatModel 同步聊天模型；为空时聊天会直接失败
     * @param streamingModel 流式聊天模型；为空时即使绑定了思考出口也走同步调用
     * @param embeddingModel 嵌入模型；为空表示这条客户端不提供嵌入
     */
    private LangChain4jLlmClient(ChatModel chatModel, StreamingChatModel streamingModel, EmbeddingModel embeddingModel) {
        this.chatModel = chatModel;
        this.streamingModel = streamingModel;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 按 DeepSeek 密钥和思考开关建只负责聊天的客户端，不挂嵌入模型。
     *
     * @param settings 含 DeepSeek 密钥、思考开关和超时的配置
     * @return 指向 DeepSeek 的聊天客户端
     */
    public static LangChain4jLlmClient deepSeek(LlmSettings settings) {
        return from(OpenAiCallConfig.deepSeek(settings));
    }

    /**
     * 按一份调用配置同时建同步和流式聊天模型，嵌入留空。测试用它指向本地假服务。
     *
     * @param config 地址、密钥、模型名、超时和思考参数
     * @return 只有聊天能力的客户端
     */
    static LangChain4jLlmClient from(OpenAiCallConfig config) {
        return new LangChain4jLlmClient(LangChain4jModels.chat(config), LangChain4jModels.streaming(config), null);
    }

    /**
     * 按网关聊天配置建客户端。设置打开嵌入且嵌入模型名非空时，再挂上嵌入模型。
     *
     * @param settings 含网关地址、密钥、聊天模型、嵌入开关和超时的配置
     * @return 指向该网关的客户端
     */
    public static LangChain4jLlmClient gateway(LlmSettings settings) {
        EmbeddingModel embedding = null;
        if (settings.gatewayEmbeddings() && !isBlank(settings.gatewayEmbeddingModel())) {
            embedding = LangChain4jModels.embedding(OpenAiCallConfig.gatewayEmbed(settings));
        }
        OpenAiCallConfig chat = OpenAiCallConfig.gatewayChat(settings);
        return new LangChain4jLlmClient(LangChain4jModels.chat(chat), LangChain4jModels.streaming(chat), embedding);
    }

    /**
     * 在虚拟线程上发起一次聊天。已绑定思考出口且存在流式模型时走流式，否则走同步补全。
     *
     * @param request 含执行编号、消息、模型和工具定义的聊天请求
     * @return 完成后给出正文、思考、工具调用和 token 用量的阶段
     */
    @Override
    public CompletionStage<LlmChatResponse> chat(LlmChatRequest request) {
        CallerCarry.Snapshot caller = CallerCarry.capture();
        return VirtualIo.supply(() -> caller.call(() -> invokeChat(request)));
    }

    /**
     * 在虚拟线程上把文本交给嵌入模型。
     *
     * @param request 含执行编号和待嵌入文本的请求
     * @return 完成后给出向量的阶段
     */
    @Override
    public CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request) {
        CallerCarry.Snapshot caller = CallerCarry.capture();
        return VirtualIo.supply(() -> caller.call(() -> invokeEmbed(request)));
    }

    /**
     * 执行一次聊天。没有聊天模型时抛出模型错误；满足流式条件时把思考增量实时推出去。
     *
     * @param request 聊天请求
     * @return 整理后的聊天结果，供应方和模型名留空，交给路由层填写
     */
    private LlmChatResponse invokeChat(LlmChatRequest request) {
        if (chatModel == null) {
            throw new AgentException(ErrorCode.LLM, "没有聊天模型", request.executionId(), null);
        }
        try {
            if (streamingModel != null && (ThinkingLive.active() || OutputLive.active())) {
                return invokeStreaming(request);
            }
            return toResponse(chatModel.chat(toRequest(request)), "", request.executionId(), false);
        } catch (RuntimeException ex) {
            throw failure(request.executionId(), ex);
        }
    }

    /**
     * 用流式模型聊天。思考推给思考出口，正文推给正文出口，上游结束前就能看到首块。流结束后再整理成聊天结果。
     *
     * @param request 聊天请求，执行编号用于推送思考增量
     * @return 含流式思考和最终正文的聊天结果
     */
    private LlmChatResponse invokeStreaming(LlmChatRequest request) {
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        StringBuilder streamed = new StringBuilder();
        streamingModel.chat(toRequest(request), new StreamingChatResponseHandler() {
            /**
             * 收到一段正文增量时立刻推给这次执行的正文出口。空片段直接忽略，不写入思考字段。
             *
             * @param partialResponse 本次流式返回的正文片段；空文本时不推送
             */
            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse == null || partialResponse.isEmpty()) {
                    return;
                }
                OutputLive.delta(request.executionId(), partialResponse);
            }

            /**
             * 收到一段思考增量时把它拼进缓冲，并立刻推给这次执行的实时出口。空片段直接忽略。
             *
             * @param partial 本次流式返回的思考片段；null 或空文本时不推送
             */
            @Override
            public void onPartialThinking(PartialThinking partial) {
                if (partial == null || partial.text() == null || partial.text().isEmpty()) {
                    return;
                }
                streamed.append(partial.text());
                ThinkingLive.delta(request.executionId(), partial.text());
            }

            /**
             * 用完整响应结束这次流式等待，让外层开始整理正文和工具调用。
             *
             * @param response 流结束后的完整聊天响应
             */
            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.complete(response);
            }

            /**
             * 把流中断记成这次等待的失败。运行时异常原样传出，其他异常包进状态异常。
             *
             * @param error 流式调用抛出的错误；null 时用固定的中断说明
             */
            @Override
            public void onError(Throwable error) {
                if (error instanceof RuntimeException runtime) {
                    done.completeExceptionally(runtime);
                    return;
                }
                done.completeExceptionally(new IllegalStateException(error == null ? "模型流中断" : error.getMessage(), error));
            }
        });
        try {
            return toResponse(done.join(), streamed.toString(), request.executionId(), true);
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw failure(request.executionId(), runtime);
            }
            throw failure(request.executionId(), new IllegalStateException(cause));
        }
    }

    /**
     * 从完整响应、原始 JSON 和已经推过的思考里整理正文、思考、工具调用和 token。流式期间没推过思考、但最终有思考时补推一次。正文若写成工具对象，则改记为工具调用并清空正文。
     *
     * @param response 模型的完整响应，可以为 null
     * @param streamedThinking 流式过程中已经拼好的思考；没有时为空串
     * @param executionId 补推思考时使用的执行编号
     * @param live 为 true 表示这次走了实时思考出口
     * @return 不含供应方和模型名的聊天结果
     */
    private LlmChatResponse toResponse(ChatResponse response, String streamedThinking, String executionId, boolean live) {
        String text = "";
        String thinking = "";
        List<LlmToolCall> calls = new ArrayList<>();
        if (response != null && response.aiMessage() != null) {
            if (response.aiMessage().text() != null) {
                text = response.aiMessage().text();
            }
            if (response.aiMessage().thinking() != null) {
                thinking = response.aiMessage().thinking();
            }
            if (response.aiMessage().hasToolExecutionRequests()) {
                for (ToolExecutionRequest call : response.aiMessage().toolExecutionRequests()) {
                    calls.add(new LlmToolCall(
                            call.id() == null ? "" : call.id(),
                            call.name() == null ? "" : call.name(),
                            call.arguments() == null ? "{}" : call.arguments()));
                }
            }
        }
        if (thinking.isBlank()) {
            thinking = reasoningFrom(response);
        }
        if (thinking.isBlank() && streamedThinking != null) {
            thinking = streamedThinking;
        }
        if (live && (streamedThinking == null || streamedThinking.isBlank()) && !thinking.isBlank()) {
            ThinkingLive.delta(executionId, thinking);
        }
        if (calls.isEmpty()) {
            calls.addAll(ToolCallText.fromCompletionJson(rawBody(response)));
        }
        if (calls.isEmpty()) {
            List<LlmToolCall> written = ToolCallText.parse(text);
            if (!written.isEmpty()) {
                calls.addAll(written);
                text = "";
            }
        }
        TokenUsage usage = response == null ? null : response.tokenUsage();
        int prompt = count(usage == null ? null : usage.inputTokenCount());
        int completion = count(usage == null ? null : usage.outputTokenCount());
        int total = usage == null || usage.totalTokenCount() == null ? prompt + completion : usage.totalTokenCount();
        return new LlmChatResponse("", "", text, prompt, completion, total, false, thinking, calls);
    }

    /**
     * 调用嵌入模型。这条客户端没有嵌入模型时，说明嵌入不走 DeepSeek。
     *
     * @param request 含执行编号和待嵌入文本的请求；文本为 null 时按空串嵌入
     * @return 向量来自网关、模型名留空的嵌入结果
     */
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

    /**
     * 从响应附带的原始 HTTP 正文里补出思考文本。
     *
     * @param response 完整聊天响应
     * @return 原始 JSON 中的思考；没有时返回空串
     */
    private static String reasoningFrom(ChatResponse response) {
        return ReasoningText.fromCompletionJson(rawBody(response));
    }

    /**
     * 取出 OpenAI 响应元数据里的原始 HTTP 正文。
     *
     * @param response 完整聊天响应；null 或没有原始正文时视为没有正文
     * @return 原始响应体；拿不到时返回空串
     */
    private static String rawBody(ChatResponse response) {
        if (response == null) {
            return "";
        }
        if (response.metadata() instanceof OpenAiChatResponseMetadata metadata
                && metadata.rawHttpResponse() != null) {
            return metadata.rawHttpResponse().body();
        }
        return "";
    }

    /**
     * 把本项目的聊天请求换成 LangChain4j 请求，带上消息、单独指定的模型名和工具规格。
     *
     * @param request 含消息、模型和工具定义的聊天请求
     * @return 可发给模型的请求
     */
    private static ChatRequest toRequest(LlmChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        if (request.messages() != null) {
            for (LlmMessage message : request.messages()) {
                messages.add(toMessage(message, request.executionId()));
            }
        }
        ChatRequest.Builder builder = ChatRequest.builder().messages(messages);
        if (request.model() != null && !request.model().isBlank()) {
            builder.modelName(request.model());
        }
        List<ToolSpecification> tools = specifications(request.tools());
        if (!tools.isEmpty()) {
            builder.toolSpecifications(tools);
        }
        return builder.build();
    }

    /**
     * 把工具定义换成 LangChain4j 的工具规格，跳过没有名字的项。
     *
     * @param tools 可用工具；null 当成没有工具
     * @return 按原顺序保留的工具规格
     */
    private static List<ToolSpecification> specifications(List<ToolDefinition> tools) {
        List<ToolSpecification> specs = new ArrayList<>();
        if (tools == null) {
            return specs;
        }
        for (ToolDefinition tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            specs.add(ToolSpecification.builder()
                    .name(tool.name())
                    .description(tool.description() == null ? "" : tool.description())
                    .parameters(schema(tool.parametersJson()))
                    .build());
        }
        return specs;
    }

    /**
     * 把 JSON Schema 里的属性收成字符串或整数参数，并带上必填名单。解析失败时给一个空对象模式。
     *
     * @param json 工具参数的 JSON Schema；空白表示没有参数
     * @return LangChain4j 用的对象参数模式
     */
    private static JsonObjectSchema schema(String json) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (json == null || json.isBlank()) {
            return builder.build();
        }
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode properties = root.get("properties");
            if (properties != null && properties.isObject()) {
                properties.fields().forEachRemaining(entry -> {
                    JsonNode property = entry.getValue();
                    String description = property.path("description").asText("");
                    if ("integer".equals(property.path("type").asText())) {
                        builder.addIntegerProperty(entry.getKey(), description);
                    } else {
                        builder.addStringProperty(entry.getKey(), description);
                    }
                });
            }
            JsonNode required = root.get("required");
            if (required != null && required.isArray() && !required.isEmpty()) {
                List<String> names = new ArrayList<>();
                required.forEach(node -> names.add(node.asText()));
                builder.required(names);
            }
        } catch (Exception ex) {
            return JsonObjectSchema.builder().build();
        }
        return builder.build();
    }

    /**
     * 按角色把一条消息换成 LangChain4j 消息。工具结果、纠正和摘要收成带前缀的用户消息，不认识的角色报模型错误。
     *
     * @param message 含角色和正文的消息
     * @param executionId 角色非法时写进错误的执行编号
     * @return 对应的聊天消息
     */
    private static ChatMessage toMessage(LlmMessage message, String executionId) {
        String role = message.role() == null ? "" : message.role();
        String text = message.text() == null ? "" : message.text();
        return switch (role) {
            case "system" -> SystemMessage.from(text);
            case "user" -> UserMessage.from(text);
            case "assistant" -> AiMessage.from(text);
            case "tool" -> UserMessage.from("工具结果:\n" + text);
            case "correction" -> UserMessage.from("纠正:\n" + text);
            case "summary" -> UserMessage.from("摘要:\n" + text);
            default -> throw new AgentException(ErrorCode.LLM, "不支持的消息角色", executionId, null);
        };
    }

    /**
     * 把客户端异常收成上层能识别的错误。业务异常原样返回，超时收成模型超时，其余说明先脱敏再标成模型错误。
     *
     * @param executionId 这次执行的编号
     * @param ex 客户端抛出的运行时异常
     * @return 交给上层的运行时异常
     */
    private static RuntimeException failure(String executionId, RuntimeException ex) {
        if (ex instanceof AgentException agent) {
            return agent;
        }
        if (LlmTimeoutException.matches(ex)) {
            return new LlmTimeoutException(executionId, "模型超时", ex);
        }
        return new AgentException(ErrorCode.LLM, AuthorizationRedactor.redact(ex.getMessage()), executionId, ex);
    }

    /**
     * 把可能缺失的 token 计数收成 0。
     *
     * @param value 用量字段；null 表示厂商没给
     * @return 计数值；null 时为 0
     */
    private static int count(Integer value) {
        return value == null ? 0 : value;
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
