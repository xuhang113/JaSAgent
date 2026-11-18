package dev.jasagent.auth;

/**
 * 一个已经落在 MySQL 里的账号。口令不在这里，角色和租户给网关做隔离。
 *
 * @param login 登录名
 * @param role {@link AccountRoles#ADMIN} 或 {@link AccountRoles#USER}
 * @param tenantId 这个用户自己的租户，不与其他用户共用
 */
public record AccountProfile(String login, String role, String tenantId) {
    /**
     * 空白字段收成空串，避免调用方再判空。
     *
     * @param login 登录名
     * @param role 角色
     * @param tenantId 租户 id
     */
    public AccountProfile {
        login = login == null ? "" : login;
        role = role == null ? "" : role;
        tenantId = tenantId == null ? "" : tenantId;
    }

    /**
     * 是不是运维角色。普通用户和缺省角色都不是。
     *
     * @return 角色正好是 admin 时为真
     */
    public boolean admin() {
        return AccountRoles.ADMIN.equals(role);
    }
}
