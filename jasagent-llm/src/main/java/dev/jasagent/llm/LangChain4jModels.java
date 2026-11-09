package dev.jasagent.llm;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/**
 * 按同一次调用配置组装 LangChain4j 的聊天、流式聊天和嵌入模型。
 */
public final class LangChain4jModels {
    /**
     * 禁止实例化。模型只通过静态工厂创建。
     */
    private LangChain4jModels() {
    }

    /**
     * 按给定地址、密钥、模型名和超时建一个同步聊天模型，并关闭把思考过程回传给服务端。
     *
     * @param config 这一次 OpenAI 兼容调用的地址、密钥、模型和超时
     * @return 可直接发起聊天补全的模型
     */
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

    /**
     * 按同一套调用配置建流式聊天模型，用来在回答结束前把思考增量推出去。
     *
     * @param config 这一次 OpenAI 兼容调用的地址、密钥、模型和超时
     * @return 可逐段接收思考和正文的流式模型
     */
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

    /**
     * 按调用配置建嵌入模型。这里不带思考开关，只保留地址、密钥、模型名和超时。
     *
     * @param config 嵌入调用的地址、密钥、模型名和超时
     * @return 可把文本换成向量的嵌入模型
     */
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
