package dev.jasagent.llm;

import dev.jasagent.llm.egress.SidecarLlmTransportBuilder;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/**
 * 按同一次调用配置组装 LangChain4j 的聊天、流式聊天和嵌入模型。
 * 离开集群的请求由 sidecar 传输改写到 {@code /v1/forward}，聊天报文仍由 LangChain4j 生成。
 */
public final class LangChain4jModels {
    /**
     * 禁止实例化。模型只通过静态工厂创建。
     */
    private LangChain4jModels() {
    }

    /**
     * 按给定地址、密钥、模型名和超时建一个同步聊天模型，并关闭把思考过程回传给服务端。
     * 传输走 sidecar：离开集群的地址会改写到 {@code /v1/forward}。
     *
     * @param config 这一次 OpenAI 兼容调用的地址、密钥、模型和超时
     * @return 可直接发起聊天补全的模型
     */
    public static OpenAiChatModel chat(OpenAiCallConfig config) {
        return OpenAiChatModel.builder()
                .httpClientBuilder(new SidecarLlmTransportBuilder())
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

    /**
     * 按同一套调用配置建流式聊天模型，用来在回答结束前把思考增量推出去。
     * 离开集群时响应仍按事件流从 sidecar 读出，不先收完整段。
     *
     * @param config 这一次 OpenAI 兼容调用的地址、密钥、模型和超时
     * @return 可逐段接收思考和正文的流式模型
     */
    public static OpenAiStreamingChatModel streaming(OpenAiCallConfig config) {
        return OpenAiStreamingChatModel.builder()
                .httpClientBuilder(new SidecarLlmTransportBuilder())
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

    /**
     * 按调用配置建嵌入模型。这里不带思考开关，只保留地址、密钥、模型名和超时。
     * 离开集群的嵌入请求同样经 sidecar 转发。
     *
     * @param config 嵌入调用的地址、密钥、模型名和超时
     * @return 可把文本换成向量的嵌入模型
     */
    public static OpenAiEmbeddingModel embedding(OpenAiCallConfig config) {
        return OpenAiEmbeddingModel.builder()
                .httpClientBuilder(new SidecarLlmTransportBuilder())
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
