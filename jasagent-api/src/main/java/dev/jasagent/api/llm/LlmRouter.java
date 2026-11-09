package dev.jasagent.api.llm;

import java.util.concurrent.CompletionStage;

/**
 * 按任务把对话补全和文本嵌入分发给具体供应方的路由端口。
 */
public interface LlmRouter {
    /**
     * 完成一次对话补全，由实现按任务和点名供应方选择模型。
     *
     * @param request 任务、消息、工具、点名供应方和执行编号
     * @return 完成后给出应答消息、token 用量、耗时和是否走了备用供应方的阶段
     */
    CompletionStage<ChatResponse> complete(ChatRequest request);

    /**
     * 把一段文本嵌入为向量，并把这次嵌入记到指定执行上。
     *
     * @param text 待嵌入的文本
     * @param executionId 这次嵌入所属的执行编号，用于追踪和调用记录
     * @return 完成后给出嵌入器名称和向量的阶段
     */
    CompletionStage<Embedding> embed(String text, String executionId);
}
