package dev.jasagent.api.tool;

/**
 * 旧的工具调用上下文，把用户、执行和参数 JSON 交给仍实现 {@link Tool#call(ToolCallContext)} 的工具。
 *
 * @param userId 发起调用的用户登录名
 * @param executionId 调用所属的执行编号
 * @param argumentsJson 参数 JSON 文本
 */
public record ToolCallContext(String userId, String executionId, String argumentsJson) {
}
