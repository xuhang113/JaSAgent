package dev.jasagent.agentpass.starter;

/**
 * agentpass 校验接口返回的报告。字段名和通行证服务的 JSON 一致。
 * 过滤器自行根据这些布尔值决定放行，不只看 {@code accepted}。
 *
 * @param proofValid 证明文是否有效
 * @param used 是否已经核销
 * @param expired 是否已经过期
 * @param userMatch 用户是否匹配
 * @param targetMatch 目标服务和工具是否都匹配
 * @param tenantMatch 租户是否匹配
 * @param sessionMatch 会话编号是否匹配
 * @param executionMatch 执行编号是否匹配
 * @param accepted 服务是否认为现在可以核销
 * @param reason 原因码；服务不可达时是 {@code unreachable}
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
     * 通行证服务没有正常返回时使用的报告。证明视为无效，过滤器会拒绝代理流量。
     *
     * @return 原因是 {@code unreachable} 的无效报告
     */
    public static VerifyReport unreachable() {
        return new VerifyReport(false, false, false, false, false, false, false, false, false, "unreachable");
    }
}
