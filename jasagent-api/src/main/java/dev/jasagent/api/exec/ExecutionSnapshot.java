package dev.jasagent.api.exec;

/**
 * 某个时刻从检查点读出的执行快照，供查询、中断和恢复判断这次运行属于谁、停在什么状态。
 *
 * @param executionId 执行编号
 * @param userId 执行所属的用户登录名，操作前用来核对调用方
 * @param sessionId 执行所属的会话；提交时没有会话则与执行编号相同
 * @param workspace 这次执行使用的工作区路径
 * @param status 读快照时的运行状态
 * @param traceId 这次执行的根追踪编号
 * @param rootSpanId 根 span 编号，子节点追踪从这里往下挂
 * @param output 已经完成的节点输出拼接结果
 * @param errorCode 失败时的错误码；没有错误时为 null
 */
public record ExecutionSnapshot(
        ExecutionId executionId,
        String userId,
        String sessionId,
        String workspace,
        ExecutionStatus status,
        String traceId,
        String rootSpanId,
        String output,
        String errorCode) {
}
