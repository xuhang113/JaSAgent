package dev.jasagent.agentpass.server;

/**
 * 核销接口的响应。除了是否核销成功，还带回和校验接口相同的各项判断，方便调用方知道为什么没核销。
 *
 * @param redeemed 这次调用是否完成了第一次核销
 * @param proofValid 证明文是否有效
 * @param used 证在这次判断时是否已经用过；成功核销后也为真
 * @param expired 是否已过期
 * @param userMatch 用户是否匹配
 * @param targetMatch 目标服务和工具是否都匹配
 * @param tenantMatch 租户是否匹配
 * @param sessionMatch 会话编号是否匹配
 * @param executionMatch 执行编号是否匹配
 * @param accepted 核销前是否满足可核销条件；成功时为真
 * @param reason 原因码
 * @param passId 通行证编号；证明无效时为空
 */
public record RedeemResponse(
        boolean redeemed,
        boolean proofValid,
        boolean used,
        boolean expired,
        boolean userMatch,
        boolean targetMatch,
        boolean tenantMatch,
        boolean sessionMatch,
        boolean executionMatch,
        boolean accepted,
        String reason,
        String passId) {

    /**
     * 用校验报告和核销结果组装响应。成功时 {@code used} 改为真，表示证已经被这次调用用掉。
     *
     * @param redeemed 这次是否核销成功
     * @param report 核销前的校验报告
     * @param reason 写给调用方的原因码
     * @param passId 通行证编号，可以为空
     * @return 核销响应
     */
    public static RedeemResponse from(boolean redeemed, VerifyReport report, String reason, String passId) {
        return new RedeemResponse(redeemed, report.proofValid(), redeemed || report.used(), report.expired(),
                report.userMatch(), report.targetMatch(), report.tenantMatch(), report.sessionMatch(),
                report.executionMatch(), report.accepted(), reason, passId);
    }
}
