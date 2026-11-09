package dev.jasagent.api.pass;

/**
 * 一次工具或插件调用的通行证，说明能否执行以及原因。等待中的通行证带有编号，供用户事后决定。
 *
 * @param passId 通行证编号；只表达决定、尚未落成记录时为 null
 * @param userId 发起调用的用户登录名；简写决定里为 null
 * @param executionId 调用所属的执行编号；简写决定里为 null
 * @param toolName 被判定的工具名；简写决定里为 null
 * @param pluginId 插件编号；普通工具或简写决定里为 null
 * @param argumentHash 参数的规范摘要；简写决定里为 null
 * @param decision 允许、拒绝、跳过或等待
 * @param reason 决定原因，例如 policy、read-only、approval-required、approved、denied
 */
public record Pass(
        String passId,
        String userId,
        String executionId,
        String toolName,
        String pluginId,
        String argumentHash,
        PassDecision decision,
        String reason) {
    /**
     * 用编号、决定和原因构造通行证，用户、执行、工具和参数摘要留空。
     *
     * @param passId 通行证编号
     * @param decision 允许、拒绝、跳过或等待
     * @param reason 决定原因
     */
    public Pass(String passId, PassDecision decision, String reason) {
        this(passId, null, null, null, null, null, decision, reason);
    }

    /**
     * 只保留决定和原因，编号与调用身份都留空。策略直接给出的结果使用这个形状。
     *
     * @param decision 允许、拒绝、跳过或等待
     * @param reason 决定原因
     */
    public Pass(PassDecision decision, String reason) {
        this(null, null, null, null, null, null, decision, reason);
    }
}
