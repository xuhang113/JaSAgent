package dev.jasagent.agentpass.server;

/**
 * 校验接口的报告。它只说明证明文、是否已用、是否过期、用户和目标是否匹配，不核销这张证。
 * {@code accepted} 只有在证明有效、未使用、未过期并且全部绑定字段都匹配时才为真。
 *
 * @param proofValid 证明文的签名和签发记录都对得上
 * @param used 这张证是否已经核销
 * @param expired 当前时刻是否已经到达或超过过期时间
 * @param userMatch 声称的登录名是否与证上的一致
 * @param targetMatch 声称的目标服务和工具名是否都与证上的一致
 * @param tenantMatch 声称的租户是否与证上的一致
 * @param sessionMatch 声称的会话编号是否与证上的一致
 * @param executionMatch 声称的执行编号是否与证上的一致
 * @param accepted 这张证现在能不能被核销
 * @param reason 说明结果的原因码；可以核销时为空
 */
public record VerifyReport(
        boolean proofValid,
        boolean used,
        boolean expired,
        boolean userMatch,
        boolean targetMatch,
        boolean tenantMatch,
        boolean sessionMatch,
        boolean executionMatch,
        boolean accepted,
        String reason) {

    /**
     * 根据各项检查组装报告，并自行计算 {@code accepted}，不信任调用方传入的总判断。
     *
     * @param proofValid 证明文是否有效
     * @param used 是否已核销
     * @param expired 是否已过期
     * @param userMatch 用户是否匹配
     * @param targetMatch 目标服务和工具是否都匹配
     * @param tenantMatch 租户是否匹配
     * @param sessionMatch 会话编号是否匹配
     * @param executionMatch 执行编号是否匹配
     * @param reason 原因码；可以核销时传空
     * @return 带有一致 {@code accepted} 的报告
     */
    public static VerifyReport of(boolean proofValid, boolean used, boolean expired, boolean userMatch,
                                  boolean targetMatch, boolean tenantMatch, boolean sessionMatch,
                                  boolean executionMatch, String reason) {
        boolean accepted = proofValid && !used && !expired && userMatch && targetMatch && tenantMatch
                && sessionMatch && executionMatch;
        return new VerifyReport(proofValid, used, expired, userMatch, targetMatch, tenantMatch, sessionMatch,
                executionMatch, accepted, reason);
    }

    /**
     * 构造一份证明无效的报告。其余匹配项都视为假，避免调用方只看某一项就放行。
     *
     * @param reason 证明无效的原因码
     * @return 不能核销的报告
     */
    public static VerifyReport invalid(String reason) {
        return of(false, false, false, false, false, false, false, false, reason);
    }
}
