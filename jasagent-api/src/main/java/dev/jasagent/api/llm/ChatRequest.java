package dev.jasagent.api.llm;

import java.util.List;

/**
 * 上下文压缩和评测裁判发给 {@link ChatModel} 的一次对话请求。
 *
 * @param task 这次调用的任务种类，压缩为 COMPRESS，评判为 JUDGE
 * @param messages 按顺序送给模型的对话
 * @param tools 允许模型调用的工具；压缩和评判通常为空
 * @param namedProvider 调用方点名的供应方；null 表示由路由自己选择
 * @param executionId 这次调用所属的执行编号，写入追踪和异常
 */
public record ChatRequest(
        LlmTask task,
        List<ChatMessage> messages,
        List<ToolDefinition> tools,
        String namedProvider,
        String executionId) {
    /**
     * 把消息和工具列表收成不可变副本。任一列表为 null 时改成空列表。
     *
     * @param task 这次调用的任务种类
     * @param messages 按顺序送给模型的对话，null 会变成空列表
     * @param tools 允许模型调用的工具，null 会变成空列表
     * @param namedProvider 调用方点名的供应方，null 表示由路由选择
     * @param executionId 这次调用所属的执行编号
     */
    public ChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * 构造一次不提供工具的对话请求。
     *
     * @param task 这次调用的任务种类
     * @param messages 按顺序送给模型的对话
     * @param namedProvider 调用方点名的供应方，null 表示由路由选择
     * @param executionId 这次调用所属的执行编号
     */
    public ChatRequest(LlmTask task, List<ChatMessage> messages, String namedProvider, String executionId) {
        this(task, messages, List.of(), namedProvider, executionId);
    }
}
