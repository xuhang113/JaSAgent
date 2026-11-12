package dev.jasagent.agentpass.server;

/**
 * 校验或核销时提交的证明，以及调用方声称的绑定字段。
 * 空白的用户、租户、会话、执行或工具名表示这次不额外对照那一项；目标给出时必须和证上的一致。
 *
 * @param ticket 证明文，领证响应里的 ticket
 * @param tenantId 调用方声称的租户；空白表示不额外对照
 * @param userId 调用方声称的登录名；空白表示不额外对照
 * @param sessionId 调用方声称的会话编号，不是登录令牌；空白表示不额外对照
 * @param executionId 调用方声称的执行编号；空白表示不额外对照
 * @param target 调用方声称的目标；过滤器应填自己的服务地址
 * @param toolName 调用方声称的工具名；空白表示不额外对照
 */
public record PassProofRequest(
        String ticket,
        String tenantId,
        String userId,
        String sessionId,
        String executionId,
        String target,
        String toolName) {
}
