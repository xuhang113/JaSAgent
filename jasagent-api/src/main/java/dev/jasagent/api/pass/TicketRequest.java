package dev.jasagent.api.pass;

/**
 * 向外部 agentpass 领取一次性证时提交的身份和目标。
 * 证只对这一次外部调用有效，不能缓存下来给下一次用。
 *
 * @param tenantId 租户 id
 * @param userId 用户 id
 * @param sessionId 会话 id
 * @param executionId 执行编号
 * @param target 即将访问的外部地址或服务器标识
 * @param toolName 工具名
 */
public record TicketRequest(
        String tenantId,
        String userId,
        String sessionId,
        String executionId,
        String target,
        String toolName) {
}
