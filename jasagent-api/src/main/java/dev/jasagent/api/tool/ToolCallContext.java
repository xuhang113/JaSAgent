package dev.jasagent.api.tool;

/**
 * 旧的工具调用上下文，把用户、执行和参数 JSON 交给仍实现 {@link Tool#call(ToolCallContext)} 的工具。
 * 租户和会话用来在外部调用前领一次性证，缺省为空串。
 *
 * @param userId 发起调用的用户登录名
 * @param executionId 调用所属的执行编号
 * @param argumentsJson 参数 JSON 文本
 * @param tenantId 租户 id，可以是空串
 * @param sessionId 会话 id，可以是空串
 */
public record ToolCallContext(String userId, String executionId, String argumentsJson, String tenantId, String sessionId) {
    /**
     * 只带用户、执行和参数的上下文。租户和会话留空。
     *
     * @param userId 发起调用的用户登录名
     * @param executionId 调用所属的执行编号
     * @param argumentsJson 参数 JSON 文本
     */
    public ToolCallContext(String userId, String executionId, String argumentsJson) {
        this(userId, executionId, argumentsJson, "", "");
    }

    /**
     * 空的租户和会话收成空串。
     *
     * @param userId 发起调用的用户登录名
     * @param executionId 调用所属的执行编号
     * @param argumentsJson 参数 JSON 文本
     * @param tenantId 租户 id，null 收成空串
     * @param sessionId 会话 id，null 收成空串
     */
    public ToolCallContext {
        tenantId = tenantId == null ? "" : tenantId;
        sessionId = sessionId == null ? "" : sessionId;
    }
}
