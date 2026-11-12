package dev.jasagent.trace.lineage;

import java.util.List;

/**
 * 已经落库的一次用户请求。
 * traceId 在网关、这次执行、模型调用和工具调用上是同一个值。
 *
 * @param traceId 贯穿这次请求的追踪标识
 * @param executionId 这次 agent 执行的标识
 * @param tenantId 租户
 * @param userId 用户
 * @param sessionId 会话
 * @param agentId 处理这次请求的 agent 实例
 * @param gateway 请求进入的网关
 * @param modelCalls 这次请求的模型调用，每条都带同一个 traceId
 * @param toolCalls 这次请求的工具调用，每条都带同一个 traceId
 */
public record RequestTrace(
        String traceId,
        String executionId,
        String tenantId,
        String userId,
        String sessionId,
        String agentId,
        String gateway,
        List<ModelCallTrace> modelCalls,
        List<ToolCallTrace> toolCalls) {

    /**
     * 把调用列表收成不可变副本。
     * 调用方之后改自己的列表，不会改到这份已记下的结果。
     */
    public RequestTrace {
        modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
