package dev.jasagent.persist;

/**
 * 一行执行记录，对应 execution 表里要跨接口传递的列。
 *
 * @param executionId 执行编号
 * @param userId 发起用户
 * @param sessionId 所属会话
 * @param workspacePath 工作区路径
 * @param status 执行状态
 * @param otelTraceId OpenTelemetry 追踪编号
 * @param otelRootSpanId 根 span 编号
 */
public record ExecutionRow(
        String executionId,
        String userId,
        String sessionId,
        String workspacePath,
        String status,
        String otelTraceId,
        String otelRootSpanId) {
}
