package dev.jasagent.agentpass.server;

import java.time.Instant;

/**
 * 一张已经签发的一次性证在内存里的状态。过期之后仍然保留，这样重放和过期可以分开判断。
 * {@link #toString()} 只输出编号，避免日志意外打出绑定字段。
 */
public final class PassState {
    private final String passId;
    private final String tenant;
    private final String user;
    private final String sessionId;
    private final String executionId;
    private final String targetService;
    private final String toolName;
    private final Instant expiresAt;
    private final boolean used;

    /**
     * 保存签发时绑定的全部字段，以及这张证有没有被核销。
     *
     * @param passId 通行证编号
     * @param tenant 租户
     * @param user 登录名
     * @param sessionId 会话编号
     * @param executionId 执行编号
     * @param targetService 目标服务
     * @param toolName 工具名
     * @param expiresAt 过期时刻
     * @param used 已经核销时为真
     */
    public PassState(String passId, String tenant, String user, String sessionId, String executionId,
                     String targetService, String toolName, Instant expiresAt, boolean used) {
        this.passId = passId;
        this.tenant = tenant;
        this.user = user;
        this.sessionId = sessionId;
        this.executionId = executionId;
        this.targetService = targetService;
        this.toolName = toolName;
        this.expiresAt = expiresAt;
        this.used = used;
    }

    /**
     * 按签名声明建立一张尚未核销的状态。声明和状态必须能逐项对上，否则以后的校验视为伪证。
     *
     * @param claims 刚签进证明文的声明
     * @return 未使用的状态
     */
    public static PassState issued(PassSigner.Claims claims) {
        return new PassState(claims.passId(), claims.tenant(), claims.user(), claims.sessionId(), claims.executionId(),
                claims.targetService(), claims.toolName(), claims.expiresAt(), false);
    }

    /**
     * 返回一张标记为已核销的副本。原对象不变，便于放进并发映射做原子替换。
     *
     * @return 字段相同但已使用的状态
     */
    public PassState redeemedCopy() {
        return new PassState(passId, tenant, user, sessionId, executionId, targetService, toolName, expiresAt, true);
    }

    /**
     * 判断证明文解开后的声明是不是这张已保存的证。任何一项不同都视为证明无效。
     *
     * @param claims 验签后的声明
     * @return 编号和全部绑定字段都一致时为真
     */
    public boolean sameClaims(PassSigner.Claims claims) {
        return passId.equals(claims.passId())
                && tenant.equals(claims.tenant())
                && user.equals(claims.user())
                && sessionId.equals(claims.sessionId())
                && executionId.equals(claims.executionId())
                && targetService.equals(claims.targetService())
                && toolName.equals(claims.toolName())
                && expiresAt.equals(claims.expiresAt());
    }

    /**
     * 返回通行证编号。
     *
     * @return 签发时生成的编号
     */
    public String passId() {
        return passId;
    }

    /**
     * 返回绑定的租户。
     *
     * @return 租户
     */
    public String tenant() {
        return tenant;
    }

    /**
     * 返回绑定的登录名。
     *
     * @return 用户登录名
     */
    public String user() {
        return user;
    }

    /**
     * 返回绑定的会话编号。这不是登录令牌。
     *
     * @return 会话编号
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * 返回绑定的执行编号。
     *
     * @return 执行编号
     */
    public String executionId() {
        return executionId;
    }

    /**
     * 返回绑定的目标服务名。
     *
     * @return 目标服务
     */
    public String targetService() {
        return targetService;
    }

    /**
     * 返回绑定的工具名。
     *
     * @return 工具名
     */
    public String toolName() {
        return toolName;
    }

    /**
     * 返回过期时刻。到达该时刻之后不能再核销。
     *
     * @return 过期时刻
     */
    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * 返回这张证是否已经核销。
     *
     * @return 已核销时为真
     */
    public boolean used() {
        return used;
    }

    /**
     * 只返回编号，避免把用户、会话或目标打进日志。
     *
     * @return 只含通行证编号的文本
     */
    @Override
    public String toString() {
        return "PassState(" + passId + ")";
    }
}
