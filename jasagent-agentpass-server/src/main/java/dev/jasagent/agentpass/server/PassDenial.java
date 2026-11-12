package dev.jasagent.agentpass.server;

/**
 * 拒绝发证时的响应。没有证明文，也没有会话令牌。
 *
 * @param issued 固定为假，表示没有发证
 * @param reason 不发证的原因码，见 {@link PassReasons}
 */
public record PassDenial(boolean issued, String reason) {
    /**
     * 用原因码构造一条未发证的响应。
     *
     * @param reason 原因码
     * @return {@code issued} 为假的拒绝
     */
    public static PassDenial of(String reason) {
        return new PassDenial(false, reason);
    }
}
