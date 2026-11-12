package dev.jasagent.sidecar;

/**
 * agentpass 校验接口返回的报告。字段名和通行证服务的 JSON 一致。
 * 是否放行看每一项布尔值，不单独信任 {@code accepted}。证明文本身不在这份报告里，也不进日志。
 *
 * @param proofValid 证明文是否有效
 * @param used 是否已经核销
 * @param expired 是否已经过期
 * @param userMatch 用户是否匹配
 * @param targetMatch 目标地址和工具名是否都匹配
 * @param tenantMatch 租户是否匹配
 * @param sessionMatch 会话编号是否匹配
 * @param executionMatch 执行编号是否匹配
 * @param accepted 服务是否认为现在可以核销
 * @param reason 原因码；服务不可达时是 {@code unreachable}
 */
public record TicketVerdict(
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
     * 通行证服务没有正常返回时使用的报告。证明视为无效，调用方不得转发。
     *
     * @return 原因是 {@code unreachable} 的无效报告
     */
    public static TicketVerdict unreachable() {
        return new TicketVerdict(false, false, false, false, false, false, false, false, false, EgressReasons.UNREACHABLE);
    }

    /**
     * 构造一份全部匹配且尚未使用的报告，供测试和成功路径对照。
     * 方法名不用 {@code accepted}，因为记录组件已经占用了这个访问器。
     *
     * @return 可以进入核销的报告
     */
    public static TicketVerdict ready() {
        return new TicketVerdict(true, false, false, true, true, true, true, true, true, null);
    }

    /**
     * 构造一份证明无效的报告。其余匹配项都视为假，避免调用方只看某一项就放行。
     *
     * @param reason 证明无效的原因码，不能含证明文
     * @return 不能核销的报告
     */
    public static TicketVerdict invalid(String reason) {
        return new TicketVerdict(false, false, false, false, false, false, false, false, false, reason);
    }

    /**
     * 判断这份报告能不能进入核销。每一项都要自己为真，服务不可达时为假。
     *
     * @return 证明有效、未使用、未过期且全部绑定匹配时为真
     */
    public boolean admissible() {
        return proofValid
                && !used
                && !expired
                && userMatch
                && targetMatch
                && tenantMatch
                && sessionMatch
                && executionMatch
                && !EgressReasons.UNREACHABLE.equals(reason);
    }

    /**
     * 把报告映射成拒绝原因。空报告和证明无效都按伪证处理；已用优先于过期和目标不匹配。
     *
     * @param verdict 校验报告，可以为空
     * @return {@link EgressReasons} 里的原因码
     */
    public static String explain(TicketVerdict verdict) {
        if (verdict == null) {
            return EgressReasons.PROOF_INVALID;
        }
        if (EgressReasons.UNREACHABLE.equals(verdict.reason)) {
            return EgressReasons.UNREACHABLE;
        }
        if (!verdict.proofValid) {
            return EgressReasons.PROOF_INVALID;
        }
        if (verdict.used || EgressReasons.SERVER_REPLAY.equals(verdict.reason)) {
            return EgressReasons.USED;
        }
        if (verdict.expired || EgressReasons.EXPIRED.equals(verdict.reason)) {
            return EgressReasons.EXPIRED;
        }
        if (!verdict.userMatch) {
            return EgressReasons.USER_MISMATCH;
        }
        if (!verdict.targetMatch || EgressReasons.TARGET_MISMATCH.equals(verdict.reason)) {
            return EgressReasons.TARGET_MISMATCH;
        }
        if (!verdict.tenantMatch || !verdict.sessionMatch || !verdict.executionMatch) {
            return EgressReasons.BINDING_MISMATCH;
        }
        return EgressReasons.REJECTED;
    }
}
