package dev.jasagent.agentpass.server;

/**
 * 领一张一次性证的请求，对应 {@code POST /v1/tickets}。
 * {@code sessionId} 是已登录会话的编号，不是登录令牌；服务用它核对用户目录里的会话文件，不会把令牌写进证、流水或日志。
 *
 * @param tenantId 租户
 * @param userId 登录名，必须和会话文件里的人一致
 * @param sessionId 会话编号，也是该用户 {@code auth/sessions} 下的文件名
 * @param executionId 这次执行的编号
 * @param target 要访问的目标，运行时示例检索是 {@code http://127.0.0.1:18751}
 * @param toolName 工具名，进程内策略按这个名字决定允许或拒绝
 */
public record IssuePassRequest(
        String tenantId,
        String userId,
        String sessionId,
        String executionId,
        String target,
        String toolName) {
}
