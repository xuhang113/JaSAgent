package dev.jasagent.api.tool;

import java.util.concurrent.CompletionStage;

/**
 * 可被目录调度的一个工具。现行执行入口接收完整 {@link ToolCall}，旧入口接收 {@link ToolCallContext}。
 */
public interface Tool {
    /**
     * 返回这个工具的名称、说明，以及它是否跳过通行证。
     *
     * @return 目录用于登记和调度的规格
     */
    ToolSpec spec();

    /**
     * 执行一次完整调用。目录在通行证允许，或工具被标成内部工具之后调用它。
     *
     * @param call 用户、执行、目录、工具名和参数
     * @return 支持该入口的实现返回工具输出阶段；默认实现在返回前抛出异常
     * @throws UnsupportedOperationException 默认实现始终抛出，消息为 call(ToolCall)
     */
    default CompletionStage<String> call(ToolCall call) {
        throw new UnsupportedOperationException("call(ToolCall)");
    }

    /**
     * 按旧的上下文执行工具。参数仍是一整段 JSON，而不是拆开的参数表。
     *
     * @param context 用户、执行编号和参数 JSON
     * @return 支持该入口的实现返回工作流工具结果阶段；默认实现在返回前抛出异常
     * @throws UnsupportedOperationException 默认实现始终抛出，消息为 call(ToolCallContext)
     */
    default CompletionStage<dev.jasagent.api.workflow.ToolResult> call(ToolCallContext context) {
        throw new UnsupportedOperationException("call(ToolCallContext)");
    }
}
