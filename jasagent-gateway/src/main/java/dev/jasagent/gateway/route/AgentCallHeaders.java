package dev.jasagent.gateway.route;

import dev.jasagent.gateway.token.InternalToken;
import org.springframework.http.HttpHeaders;

/**
 * 网关调用 agent 时使用的请求头。名字与 agent 运行时约定一致，不能另起。
 * 令牌放在 {@code X-Jasagent-Token}，值只来自环境变量 {@code JASAGENT_INTERNAL_TOKEN}。
 * 租户、用户、会话分别放在 {@code X-Jasagent-Tenant}、{@code X-Jasagent-User}、{@code X-Jasagent-Session}。
 * 会话头放的是会话编号，不是登录令牌。
 */
public final class AgentCallHeaders {
    /**
     * 内部令牌的头名。agent 只认这个名字。
     */
    public static final String TOKEN = "X-Jasagent-Token";
    /**
     * 租户 id 的头名。
     */
    public static final String TENANT = "X-Jasagent-Tenant";
    /**
     * 用户 id 的头名。值是登录名。
     */
    public static final String USER = "X-Jasagent-User";
    /**
     * 会话 id 的头名。值是会话编号，不是 Cookie 里的登录令牌。
     */
    public static final String SESSION = "X-Jasagent-Session";
    /**
     * 内部令牌的环境变量名。网关只从这里读取，不写进仓库。
     */
    public static final String TOKEN_ENV = "JASAGENT_INTERNAL_TOKEN";
    /**
     * 没有单独配置租户时使用的租户 id，和 agent 的本地租户一致。
     */
    public static final String TENANT_LOCAL = "local";

    /**
     * 禁止实例化。头名只通过常量使用。
     */
    private AgentCallHeaders() {
    }

    /**
     * 清掉浏览器带来的登录凭据、伪造的内部头，以及不是约定名字的旧头，再写上这次调用的四个头。
     * 令牌未配置时拒绝，避免发出空令牌。本方法不写日志。
     *
     * @param headers 即将转发给 agent 的头，会被就地修改
     * @param token 内部令牌，必须已经配置
     * @param tenantId 租户 id
     * @param userId 用户 id，即登录名
     * @param sessionId 会话编号
     */
    public static void apply(HttpHeaders headers, InternalToken token, String tenantId, String userId, String sessionId) {
        if (token == null || !token.configured()) {
            throw new IllegalStateException("未配置内部令牌");
        }
        headers.remove(HttpHeaders.COOKIE);
        headers.remove(HttpHeaders.AUTHORIZATION);
        headers.remove(TOKEN);
        headers.remove(TENANT);
        headers.remove(USER);
        headers.remove(SESSION);
        headers.remove("X-Jasagent-Internal-Token");
        headers.remove("X-Jasagent-Tenant-Id");
        headers.remove("X-Jasagent-User-Id");
        headers.remove("X-Jasagent-Session-Id");
        headers.set(TOKEN, token.headerValue());
        headers.set(TENANT, tenantId == null ? "" : tenantId);
        headers.set(USER, userId == null ? "" : userId);
        headers.set(SESSION, sessionId == null ? "" : sessionId);
    }
}
