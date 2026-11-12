package dev.jasagent.trace.lineage;

/**
 * 一次工具调用在请求溯源里的位置。
 * 记录请求时 traceId 和 executionId 可以空着，写入后会改成和这次请求相同。
 *
 * @param callId 这次工具调用的标识，同一次执行内不能重复
 * @param toolName 工具名
 * @param traceId 贯穿这次请求的追踪标识；记录时可以空
 * @param executionId 所属执行；记录时可以空
 */
public record ToolCallTrace(
        String callId,
        String toolName,
        String traceId,
        String executionId) {
}
