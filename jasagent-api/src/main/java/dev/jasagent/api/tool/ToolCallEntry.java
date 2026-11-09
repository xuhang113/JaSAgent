package dev.jasagent.api.tool;

import dev.jasagent.api.pass.PassDecision;

/**
 * 目录记下的一条工具调用流水，保留参数摘要和通行证决定，便于事后核对这次调用为什么执行或被拦住。
 *
 * @param executionId 调用所属的执行编号
 * @param userId 发起调用的用户登录名
 * @param toolName 工具名
 * @param argumentHash 参数的规范摘要；内部工具没有摘要时为 null
 * @param passId 通行证编号；内部工具没有通行证时为 null
 * @param decision 通行证决定；内部工具为 null
 * @param reason 决定原因，例如 policy、read-only、approval-required；没有通行证时为 null
 * @param executed 工具体是否已经执行
 * @param output 工具输出；未执行时为空串
 */
public record ToolCallEntry(
        String executionId,
        String userId,
        String toolName,
        String argumentHash,
        String passId,
        PassDecision decision,
        String reason,
        boolean executed,
        String output) {
}
