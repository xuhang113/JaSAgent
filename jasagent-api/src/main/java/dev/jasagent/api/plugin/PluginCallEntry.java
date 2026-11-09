package dev.jasagent.api.plugin;

import dev.jasagent.api.pass.PassDecision;

/**
 * 一次插件调用落下来的审计记录。
 * 宿主在给出结果之前先把它追加到调用记录里。
 *
 * @param executionId 所属执行
 * @param userId 发起调用的用户
 * @param pluginId 插件标识
 * @param phase 时机名称；当时没有时机时为空串
 * @param argumentHash 用时机、参数和模型输出算出的参数哈希
 * @param passId 通行证标识，没有通行证时为空
 * @param decision 通行证决定，没有时为空
 * @param reason 决定原因，没有时为空
 * @param executed 插件体是否已经执行
 * @param failed 插件体是否抛错
 * @param output 插件输出；抛错时是错误说明
 */
public record PluginCallEntry(
        String executionId,
        String userId,
        String pluginId,
        String phase,
        String argumentHash,
        String passId,
        PassDecision decision,
        String reason,
        boolean executed,
        boolean failed,
        String output) {
}
