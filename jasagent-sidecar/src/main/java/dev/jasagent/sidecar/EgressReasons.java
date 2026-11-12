package dev.jasagent.sidecar;

/**
 * 拒绝出站时写给调用方的原因码。这些字符串会出现在响应里，因此不能带通行证、密钥或上游正文。
 */
public final class EgressReasons {
    /**
     * 目的地址不是可用的 http 或 https 绝对地址，或者带了用户信息，无法判断主机。
     */
    public static final String BAD_TARGET = "bad-target";
    /**
     * 方法不是 sidecar 愿意转发的那几种。TRACE 会把请求头回显给上游，所以也不转发。
     */
    public static final String METHOD_NOT_ALLOWED = "method-not-allowed";
    /**
     * 主机不在免证名单里，请求也没有带通行证。
     */
    public static final String MISSING_TICKET = "missing-ticket";
    /**
     * 证明文无效，或和 agentpass 的签发记录对不上。对应调用方说的伪证。
     */
    public static final String PROOF_INVALID = "proof-invalid";
    /**
     * 通行证服务没有按约定返回。此时不转发，避免在核对失败时把请求放出去。
     */
    public static final String UNREACHABLE = "unreachable";
    /**
     * 这张证已经核销过。agentpass 的原因码 {@code replay} 也归到这里。
     */
    public static final String USED = "used";
    /**
     * 证明文有效，但已经过期。
     */
    public static final String EXPIRED = "expired";
    /**
     * 证上的用户和这次请求声称的用户不一致。
     */
    public static final String USER_MISMATCH = "user-mismatch";
    /**
     * 证上的目标和服务这次实际要转发的地址不一致，或工具名对不上。
     */
    public static final String TARGET_MISMATCH = "target-mismatch";
    /**
     * 租户、会话编号或执行编号和证上的绑定不一致。
     */
    public static final String BINDING_MISMATCH = "binding-mismatch";
    /**
     * 报告能读到，但没有落到上面更具体的原因。仍然不转发。
     */
    public static final String REJECTED = "rejected";
    /**
     * 通行证已经通过，但上游连接失败或超时。异常原文不进入响应。
     */
    public static final String UPSTREAM_FAILED = "upstream-failed";
    /**
     * agentpass 表示这张证已经用过时使用的原因码。对外仍写成 {@link #USED}。
     */
    public static final String SERVER_REPLAY = "replay";

    /**
     * 禁止实例化。原因码只通过常量使用。
     */
    private EgressReasons() {
    }

    /**
     * 把原因码映射成 HTTP 状态。认不出证用 401，认得但不能用用 403，地址或方法本身不合法用 400 或 405。
     *
     * @param reason {@link EgressReasons} 里的原因码
     * @return 写给调用方的状态码；未知原因按 403，避免把没核对清楚的请求说成成功
     */
    public static int status(String reason) {
        if (BAD_TARGET.equals(reason)) {
            return 400;
        }
        if (METHOD_NOT_ALLOWED.equals(reason)) {
            return 405;
        }
        if (MISSING_TICKET.equals(reason) || PROOF_INVALID.equals(reason) || UNREACHABLE.equals(reason)) {
            return 401;
        }
        if (UPSTREAM_FAILED.equals(reason)) {
            return 502;
        }
        return 403;
    }
}
