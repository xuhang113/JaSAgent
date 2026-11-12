package dev.jasagent.agentpass.starter;

/**
 * 过滤器拿去和通行证服务对照的绑定字段。目标服务来自本服务配置，不来自可伪造的客户端头。
 *
 * @param tenant 租户
 * @param user 登录名
 * @param sessionId 会话编号，不是登录令牌
 * @param executionId 执行编号
 * @param targetService 本服务的名字
 * @param toolName 这次调用的工具名
 */
public record PassCall(
        String tenant,
        String user,
        String sessionId,
        String executionId,
        String targetService,
        String toolName) {
}
