package dev.jasagent.agentpass.server;

import java.time.Instant;

/**
 * 流水里的一条记录。字段只保留谁在什么时候对哪个目标做了哪一种决定，不放会话令牌和证明文。
 *
 * @param kind 领证、核销、拒绝或重放
 * @param passId 通行证编号；还没发证时为空
 * @param tenant 请求里的租户；缺失时为空字符串
 * @param user 请求里的登录名；缺失时为空字符串
 * @param executionId 执行编号；缺失时为空字符串
 * @param targetService 目标服务名；缺失时为空字符串
 * @param toolName 工具名；缺失时为空字符串
 * @param reason 原因码，取值见 {@link PassReasons}
 * @param at 记入流水的时刻
 */
public record AuditEvent(
        AuditKind kind,
        String passId,
        String tenant,
        String user,
        String executionId,
        String targetService,
        String toolName,
        String reason,
        Instant at) {
}
