package dev.jasagent.sidecar;

/**
 * 拿去和已有 agentpass 对照的绑定字段。目标只来自即将转发的规范地址，不来自调用方另报的服务名。
 *
 * @param tenantId 调用方声称的租户；空白表示不额外对照
 * @param userId 调用方声称的登录名；空白表示不额外对照
 * @param sessionId 调用方声称的会话编号，不是登录令牌；空白表示不额外对照
 * @param executionId 调用方声称的执行编号；空白表示不额外对照
 * @param target 即将转发的规范绝对地址，必须和证上的目标完全一致
 * @param toolName 调用方声称的工具名；空白表示不额外对照
 */
public record TicketCall(
        String tenantId,
        String userId,
        String sessionId,
        String executionId,
        String target,
        String toolName) {
}
