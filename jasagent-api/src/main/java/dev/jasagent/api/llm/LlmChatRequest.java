package dev.jasagent.api.llm;

import java.util.List;

/**
 * 发给模型客户端的一次对话请求。路由按任务、点名供应方和点名模型决定走 DeepSeek 还是 AI Gateway。
 *
 * @param executionId 这次调用所属的执行编号，写入调用记录、追踪和异常
 * @param task 路由任务种类；REACT 可以走网关并在超时后重试，其余任务固定走 DeepSeek
 * @param provider 调用方点名的供应方，取 deepseek 或 ai-gateway；空值表示按任务选择
 * @param messages 按顺序送给模型的对话，角色包括 system、user、assistant、tool、correction、summary
 * @param remainingSteps 当前执行还剩的步数；REACT 超时后，该值至少为 1 时路由会再试一次
 * @param model 调用方点名的模型；空值表示使用该供应方的默认模型
 * @param tools 允许模型调用的工具定义；空列表表示这次补全不提供工具
 */
public record LlmChatRequest(
        String executionId,
        LlmTask task,
        String provider,
        List<LlmMessage> messages,
        int remainingSteps,
        String model,
        List<ToolDefinition> tools) {

    /**
     * 规范化工具列表。tools 为 null 时改成空列表，否则复制为不可变列表。
     *
     * @param executionId 这次调用所属的执行编号
     * @param task 路由任务种类
     * @param provider 调用方点名的供应方，空值表示按任务选择
     * @param messages 按顺序送给模型的对话
     * @param remainingSteps 当前执行还剩的步数
     * @param model 调用方点名的模型，空值表示使用默认模型
     * @param tools 允许模型调用的工具定义，null 会变成空列表
     */
    public LlmChatRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * 构造一次不提供工具的对话请求，模型名按传入值保留。
     *
     * @param executionId 这次调用所属的执行编号
     * @param task 路由任务种类
     * @param provider 调用方点名的供应方，空值表示按任务选择
     * @param messages 按顺序送给模型的对话
     * @param remainingSteps 当前执行还剩的步数
     * @param model 调用方点名的模型，空值表示使用默认模型
     */
    public LlmChatRequest(
            String executionId,
            LlmTask task,
            String provider,
            List<LlmMessage> messages,
            int remainingSteps,
            String model) {
        this(executionId, task, provider, messages, remainingSteps, model, List.of());
    }

    /**
     * 构造一次不点名模型、也不提供工具的对话请求，模型由路由按供应方默认值决定。
     *
     * @param executionId 这次调用所属的执行编号
     * @param task 路由任务种类
     * @param provider 调用方点名的供应方，空值表示按任务选择
     * @param messages 按顺序送给模型的对话
     * @param remainingSteps 当前执行还剩的步数
     */
    public LlmChatRequest(String executionId, LlmTask task, String provider, List<LlmMessage> messages, int remainingSteps) {
        this(executionId, task, provider, messages, remainingSteps, null, List.of());
    }
}
