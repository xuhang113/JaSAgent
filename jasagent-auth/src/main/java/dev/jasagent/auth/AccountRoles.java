package dev.jasagent.auth;

/**
 * 账号角色和 {@code GET /api/me} 的字段名。
 * admin 是运维特权，不是某个普通租户里的成员。自助注册只得到 user。
 * 字段名在这里固定，页面和测试都按这两个字符串读取。
 */
public final class AccountRoles {
    /**
     * {@code GET /api/me} 里表示角色的字段名。值是 {@link #ADMIN} 或 {@link #USER}。
     */
    public static final String FIELD = "role";
    /**
     * {@code GET /api/me} 里表示该用户自己的租户的字段名。一人一租户，不接受客户端指定。
     */
    public static final String TENANT_FIELD = "tenantId";
    /**
     * 运维角色。可以调用集群接口，但不能因此读取他人会话。
     */
    public static final String ADMIN = "admin";
    /**
     * 自助注册得到的普通角色。不能看集群。
     */
    public static final String USER = "user";
    /**
     * 迁库前聊天会话使用的共享租户。与账本和网关里的 local 相同。
     * 运维账号迁入后，属于该登录名的旧行会改到他自己的租户。
     */
    public static final String LEGACY_TENANT = "local";

    /**
     * 禁止实例化。角色只通过常量使用。
     */
    private AccountRoles() {
    }
}
