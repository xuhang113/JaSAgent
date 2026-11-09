package dev.jasagent.api.llm;

import java.util.concurrent.CompletionStage;

/**
 * 对话模型端口。上下文窗口用它压缩较早的对话，评测器用它评判一次运行。
 */
public interface ChatModel {
    /**
     * 按请求中的任务和消息完成一次对话补全。
     *
     * @param request 任务、消息、工具、点名供应方和执行编号
     * @return 完成后给出应答消息、用量和耗时的阶段；实现返回 null 时调用方按失败处理
     */
    CompletionStage<ChatResponse> complete(ChatRequest request);

    /**
     * 把一段文本嵌入为向量，并把这次嵌入记到指定执行上。
     *
     * @param text 待嵌入的文本
     * @param executionId 这次嵌入所属的执行编号，用于追踪和错误
     * @return 完成后给出嵌入器名称和向量的阶段
     */
    CompletionStage<Embedding> embed(String text, String executionId);
}
