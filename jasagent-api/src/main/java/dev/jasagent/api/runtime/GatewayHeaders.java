package dev.jasagent.api.runtime;

/**
 * 网关调用 agent 时使用的请求头和固定文案。
 * agent 不再核对自己的登录口令，只核对内部令牌，并从这些头里读取租户、用户和会话。
 */
public final class GatewayHeaders {
    /**
     * 内部令牌所在的请求头。值只与进程配置比较，不能写入日志、追踪或异常。
     */
    public static final String TOKEN = "X-Jasagent-Token";
    /**
     * 网关传来的租户 id。和用户 id、会话 id 一起构成隔离键。
     */
    public static final String TENANT = "X-Jasagent-Tenant";
    /**
     * 网关传来的用户 id。必须与会话在库里的所有者一致，否则拒绝执行。
     */
    public static final String USER = "X-Jasagent-User";
    /**
     * 网关传来的会话 id。空白表示这次调用还没有指定会话。
     */
    public static final String SESSION = "X-Jasagent-Session";
    /**
     * 没有显式租户时使用的租户 id。同一进程里的直接调用也落在这个租户下。
     */
    public static final String TENANT_LOCAL = "local";
    /**
     * 网关用户与会话所有者不一致时返回的说明。调用方可以据此区分认证失败，且不含密钥。
     */
    public static final String OWNER_MISMATCH = "网关用户与会话所有者不一致";

    /**
     * 禁止实例化。头名和文案只通过常量使用。
     */
    private GatewayHeaders() {
    }
}
