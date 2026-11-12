package dev.jasagent.agentpass.server;

import java.time.Instant;

/**
 * 签发成功后交回调用方的一次性证。运行时只读取 {@code ticket}；其余字段说明这张证绑了什么。
 * 会话只给编号，不给登录令牌。
 *
 * @param issued 固定为真，和拒绝响应里的 {@code issued=false} 对应
 * @param passId 通行证编号
 * @param ticket 证明文。运行时把它放进 {@code X-Jasagent-Ticket}
 * @param tenantId 绑好的租户
 * @param userId 绑好的登录名
 * @param sessionId 绑好的会话编号
 * @param executionId 绑好的执行编号
 * @param target 绑好的目标
 * @param toolName 绑好的工具名
 * @param expiresAt 过期时刻，到达后不能核销
 */
public record IssuedPass(
        boolean issued,
        String passId,
        String ticket,
        String tenantId,
        String userId,
        String sessionId,
        String executionId,
        String target,
        String toolName,
        Instant expiresAt) {
}
