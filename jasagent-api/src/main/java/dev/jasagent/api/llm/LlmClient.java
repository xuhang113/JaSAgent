package dev.jasagent.api.llm;

import java.util.concurrent.CompletionStage;

/**
 * 单个供应方或聚合路由的模型调用端口。现行入口是带请求对象的对话补全和文本嵌入。
 */
public interface LlmClient {
    /**
     * 按请求里的任务、消息和工具定义发起一次对话补全。
     *
     * @param request 执行编号、任务、供应方、消息、剩余步数、模型和工具定义
     * @return 完成后给出正文、token 用量、思考文本和工具调用的阶段
     */
    CompletionStage<LlmChatResponse> chat(LlmChatRequest request);

    /**
     * 把请求中的文本转成向量。
     *
     * @param request 执行编号和待嵌入的文本
     * @return 完成后给出嵌入器、模型和向量的阶段
     */
    CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request);

    /**
     * 声明这个客户端对应的供应方枚举。
     *
     * @return 默认返回 null，表示客户端没有绑定 {@link LlmProvider}
     */
    default LlmProvider provider() {
        return null;
    }

    /**
     * 用旧的对话请求发起补全。默认实现不发起调用。
     *
     * @param request 任务、消息、工具、点名供应方和执行编号
     * @return 支持该入口的实现返回补全阶段；默认实现在返回前抛出异常
     * @throws UnsupportedOperationException 默认实现始终抛出，消息为 chat(ChatRequest)
     */
    default CompletionStage<ChatResponse> chat(ChatRequest request) {
        throw new UnsupportedOperationException("chat(ChatRequest)");
    }

    /**
     * 用纯文本发起嵌入。默认实现不发起调用。
     *
     * @param text 待嵌入的文本
     * @param executionId 这次嵌入所属的执行编号，用于追踪和错误
     * @return 支持该入口的实现返回向量阶段；默认实现在返回前抛出异常
     * @throws UnsupportedOperationException 默认实现始终抛出，消息为 embed(text)
     */
    default CompletionStage<Embedding> embed(String text, String executionId) {
        throw new UnsupportedOperationException("embed(text)");
    }
}
