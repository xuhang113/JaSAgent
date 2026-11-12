package dev.jasagent.gateway.store;

import java.util.List;
import java.util.Optional;

/**
 * 用户到 agent 实例的粘性。网关重启后仍要能读回，所以实现落在 MySQL，而不是进程内存。
 */
public interface AffinityStore {
    /**
     * 查出这个租户下该用户当前粘着的实例编号。没有记录时为空。
     *
     * @param tenantId 租户 id
     * @param userId 用户 id，即登录名
     * @return 实例编号；没有粘性时为空
     */
    Optional<String> find(String tenantId, String userId);

    /**
     * 写下或改写这个用户的粘性实例。同一租户和用户只保留一行。
     *
     * @param tenantId 租户 id
     * @param userId 用户 id
     * @param instanceId 实例编号，例如 agent-18731
     */
    void save(String tenantId, String userId, String instanceId);

    /**
     * 列出这个租户下全部粘性，按用户 id 排序。集群页用它汇总每台实例上的登录名。
     *
     * @param tenantId 租户 id
     * @return 粘性行；没有时是空列表
     */
    List<AffinityRow> list(String tenantId);

    /**
     * 一行粘性。用户 id 是登录名，实例编号对应 {@code agent-端口}。
     *
     * @param userId 登录名
     * @param instanceId 实例编号
     */
    record AffinityRow(String userId, String instanceId) {
    }
}
