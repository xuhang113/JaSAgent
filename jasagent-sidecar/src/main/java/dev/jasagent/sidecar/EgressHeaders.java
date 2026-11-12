package dev.jasagent.sidecar;

/**
 * agent 把出站请求交给 sidecar 时使用的请求头。值由调用方传入，本进程只读取，不把它们写进日志。
 * 这些头在转发给上游之前会被去掉，避免把通行证送到外部主机。
 */
public final class EgressHeaders {
    /**
     * 真正要访问的绝对地址，例如 {@code https://api.deepseek.com/chat/completions}。
     * 放行和通行证上的目标都只认这个头，不认 {@code Host} 或 {@code X-Forwarded-Host}。
     */
    public static final String URL = "X-Jasagent-Url";
    /**
     * 一次性通行证的证明文。大模型主机可以不带；其它主机没有这个头也不带 {@link #PASS} 时拒绝。
     */
    public static final String TICKET = "X-Jasagent-Ticket";
    /**
     * 证明文的另一个头，和 {@link #TICKET} 相同。只有票据头缺失时才读它。
     */
    public static final String PASS = "X-Jasagent-Pass";
    /**
     * 调用方声称的租户。空白表示不让 agentpass 额外对照租户。
     */
    public static final String TENANT = "X-Jasagent-Tenant";
    /**
     * 调用方声称的登录名，有内容时必须和证上的用户一致。
     */
    public static final String USER = "X-Jasagent-User";
    /**
     * 调用方声称的会话编号。这是会话编号，不是登录令牌。
     */
    public static final String SESSION = "X-Jasagent-Session";
    /**
     * 调用方声称的执行编号。
     */
    public static final String EXECUTION = "X-Jasagent-Execution";
    /**
     * 调用方声称的工具名。空白时 agentpass 不额外对照工具名；目标地址始终要对照。
     */
    public static final String TOOL = "X-Jasagent-Tool";

    /**
     * 禁止实例化。请求头名字只通过常量使用。
     */
    private EgressHeaders() {
    }
}
