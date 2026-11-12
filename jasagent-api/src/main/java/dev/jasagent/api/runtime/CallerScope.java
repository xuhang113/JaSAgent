package dev.jasagent.api.runtime;

/**
 * 一次请求里网关传来的租户、用户和会话。
 * 过滤器在请求线程上绑定它；工作线程必须把这三个值抄到执行对象上，不能在请求结束后再读线程局部变量。
 *
 * @param tenantId 租户 id，空白时按本地租户处理
 * @param userId 网关用户 id，不能为空白
 * @param sessionId 网关会话 id，可以是空白
 */
public record CallerScope(String tenantId, String userId, String sessionId) {
    /**
     * 请求属性名。过滤器把身份放在这里，控制器再取出来。
     */
    public static final String ATTRIBUTE = "jasagent.caller";
    /**
     * 当前请求线程上的身份。工作线程上看不到请求线程里绑定的值。
     */
    private static final ThreadLocal<CallerScope> CURRENT = new ThreadLocal<>();

    /**
     * 去掉空白。租户空白时改成本地租户，用户和会话空白时改成空串。
     *
     * @param tenantId 网关租户 id，可以是空白
     * @param userId 网关用户 id，可以是空白
     * @param sessionId 网关会话 id，可以是空白
     */
    public CallerScope {
        tenantId = tenantId == null || tenantId.isBlank() ? GatewayHeaders.TENANT_LOCAL : tenantId.trim();
        userId = userId == null ? "" : userId.trim();
        sessionId = sessionId == null ? "" : sessionId.trim();
    }

    /**
     * 把身份绑到当前线程。请求结束时必须 {@link #clear()}，避免线程复用时串到下一次调用。
     *
     * @param scope 这次请求的身份；null 表示清掉
     */
    public static void bind(CallerScope scope) {
        if (scope == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(scope);
    }

    /**
     * 去掉当前线程上的身份。
     * 副作用：之后 {@link #current()} 返回 null。
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 读取当前线程上的身份。
     *
     * @return 过滤器绑定的身份；不在请求线程上或已经清掉时为 null
     */
    public static CallerScope current() {
        return CURRENT.get();
    }

    /**
     * 当前线程的租户。没有绑定时用本地租户，保证直接调用和网关调用落在稳定的键上。
     *
     * @return 非空白的租户 id
     */
    public static String tenantOrLocal() {
        CallerScope scope = current();
        if (scope == null || scope.tenantId == null || scope.tenantId.isBlank()) {
            return GatewayHeaders.TENANT_LOCAL;
        }
        return scope.tenantId;
    }
}
