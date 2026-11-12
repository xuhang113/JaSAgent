package dev.jasagent.agentpass.server;

/**
 * 领证、校验和核销对外使用的原因码。这些字符串会出现在响应和流水里，因此不能带登录令牌或证明文。
 */
public final class PassReasons {
    /**
     * 领证请求缺少租户、用户、会话、执行、目标服务或工具名中的任何一项。
     */
    public static final String INCOMPLETE = "incomplete";

    /**
     * 会话令牌对不上任何已登录用户，或者登录名本身不能作为用户目录名。
     */
    public static final String NOT_LOGGED_IN = "not-logged-in";

    /**
     * 请求里的用户和会话令牌所属的登录名不是同一个人。
     */
    public static final String SESSION_MISMATCH = "session-mismatch";

    /**
     * 用户策略把该工具写成拒绝，所以不发一次性通行证。
     */
    public static final String POLICY_DENY = "policy-deny";

    /**
     * 进程内门返回等待审批。一次性证只在允许时签发，等待中的调用不发证，也不改挂起文件。
     */
    public static final String APPROVAL_REQUIRED = "approval-required";

    /**
     * 进程内门显式关闭。本服务仍然不发证，避免把“未启用”当成已经授权。
     */
    public static final String AGENTPASS_NOT_ENABLED = "agentpass-not-enabled";

    /**
     * 策略文件存在但读不出来或格式不对。不发证，响应里不带文件内容。
     */
    public static final String POLICY_UNREADABLE = "policy-unreadable";

    /**
     * 核对登录或策略时出现了预期之外的失败。不发证，响应里不带底层消息。
     */
    public static final String ACCESS_CHECK_FAILED = "access-check-failed";

    /**
     * 已经签发。流水用这个原因记下领证，响应正文另有证明文。
     */
    public static final String ISSUED = "issued";

    /**
     * 证明文验签失败、结构不对，或和内存里的签发记录对不上。
     */
    public static final String PROOF_INVALID = "proof-invalid";

    /**
     * 证明文有效，但当前时间已经到达或超过过期时间。
     */
    public static final String EXPIRED = "expired";

    /**
     * 这张证已经核销过。再次核销记为重放，不改变第一次核销的结果。
     */
    public static final String REPLAY = "replay";

    /**
     * 证明文里的用户和这次校验给出的用户不一致。
     */
    public static final String USER_MISMATCH = "user-mismatch";

    /**
     * 目标服务或工具名和签发时绑定的不一致。
     */
    public static final String TARGET_MISMATCH = "target-mismatch";

    /**
     * 租户、会话编号或执行编号和签发时绑定的不一致。
     */
    public static final String BINDING_MISMATCH = "binding-mismatch";

    /**
     * 第一次核销成功。之后同一张证只能得到重放。
     */
    public static final String REDEEMED = "redeemed";

    /**
     * 禁止实例化。原因码只通过常量使用。
     */
    private PassReasons() {
    }
}
