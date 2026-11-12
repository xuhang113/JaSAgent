package dev.jasagent.trace.lineage;

import java.util.List;

/**
 * 要记下的一次用户请求。
 * 网关或组装层在知道租户、用户、会话和接到哪台 agent 之后调用。
 * traceId 空着时由 {@link RequestLineage#recordRequest} 生成，并写到每条模型调用和工具调用上。
 * 同一次 executionId 再记一次会整行替换，模型调用和工具调用也换成这一次带上的列表，不追加。
 *
 * @param traceId 已有的追踪标识；没有就传 null 或空白
 * @param executionId 这次 agent 执行的标识
 * @param tenantId 租户
 * @param userId 用户
 * @param sessionId 会话
 * @param agentId 处理这次请求的 agent 实例，用来回答「打到哪台 agent」
 * @param gateway 请求进入的网关标识，不要放密钥
 * @param modelCalls 这次请求里的模型调用；没有就传 null 或空列表
 * @param toolCalls 这次请求里的工具调用；没有就传 null 或空列表
 */
public record RequestTraceCommand(
        String traceId,
        String executionId,
        String tenantId,
        String userId,
        String sessionId,
        String agentId,
        String gateway,
        List<ModelCallTrace> modelCalls,
        List<ToolCallTrace> toolCalls) {
}
