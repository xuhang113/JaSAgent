package dev.jasagent.agentpass.starter;

/**
 * 代理流量携带通行证和绑定字段时使用的请求头。值由调用方传入，过滤器只读取，不把它们写进日志。
 */
public final class AgentPassHeaders {
    /**
     * 标记这是代理流量。{@code true}、{@code 1}、{@code yes} 以及其他无法识别的值都视为代理；{@code false}、{@code 0}、{@code no} 不是。
     */
    public static final String AGENT = "X-Jasagent-Agent";

    /**
     * 运行时放一次性证的请求头。只要这个头出现，哪怕值为空，请求也按代理流量处理。
     */
    public static final String TICKET = "X-Jasagent-Ticket";

    /**
     * 一次性通行证的另一请求头，和 {@link #TICKET} 一样对待。只要出现，哪怕值为空，请求也按代理流量处理。
     */
    public static final String PASS = "X-Jasagent-Pass";

    /**
     * 调用方声称的租户。没有这个头时用服务配置里的租户。
     */
    public static final String TENANT = "X-Jasagent-Tenant";

    /**
     * 调用方声称的登录名，必须和证上的用户一致。
     */
    public static final String USER = "X-Jasagent-User";

    /**
     * 调用方声称的会话编号，不是登录令牌。
     */
    public static final String SESSION = "X-Jasagent-Session";

    /**
     * 调用方声称的执行编号。
     */
    public static final String EXECUTION = "X-Jasagent-Execution";

    /**
     * 调用方声称的工具名。没有这个头时用服务配置里的工具名。
     */
    public static final String TOOL = "X-Jasagent-Tool";

    /**
     * 禁止实例化。请求头名字只通过常量使用。
     */
    private AgentPassHeaders() {
    }
}
