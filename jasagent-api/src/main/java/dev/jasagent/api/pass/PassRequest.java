package dev.jasagent.api.pass;

import java.nio.file.Path;

/**
 * 向通行证门申请一次工具或插件调用的材料。参数摘要用来把同一次调用和已有批准对应起来。
 *
 * @param userId 发起调用的用户登录名
 * @param executionId 调用所属的执行编号
 * @param userDirectory 该用户的 jasagent 目录，门从这里读策略和挂起记录；简写请求里为 null
 * @param workspaceDirectory 工作区里的 jasagent 目录；与这次判定无关时为 null
 * @param toolName 待判定的工具名
 * @param pluginId 插件编号；普通工具为 null
 * @param argumentHash 参数的规范摘要
 */
public record PassRequest(
        String userId,
        String executionId,
        Path userDirectory,
        Path workspaceDirectory,
        String toolName,
        String pluginId,
        String argumentHash) {
    /**
     * 构造一份不带目录的申请，参数摘要写入 {@code argumentHash}。
     *
     * @param userId 发起调用的用户登录名
     * @param executionId 调用所属的执行编号
     * @param toolName 待判定的工具名
     * @param pluginId 插件编号；普通工具为 null
     * @param argumentsHash 参数的规范摘要，存入 argumentHash
     */
    public PassRequest(String userId, String executionId, String toolName, String pluginId, String argumentsHash) {
        this(userId, executionId, null, null, toolName, pluginId, argumentsHash);
    }

    /**
     * 返回参数的规范摘要，与 {@code argumentHash} 是同一个值。
     *
     * @return 参数摘要
     */
    public String argumentsHash() {
        return argumentHash;
    }
}
