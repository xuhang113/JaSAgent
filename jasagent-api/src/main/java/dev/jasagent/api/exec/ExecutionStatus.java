package dev.jasagent.api.exec;

/**
 * 一次执行在运行控制里的状态。成功、失败、取消和挂起都算可以停下来查看的状态。
 */
public enum ExecutionStatus {
    /** 执行已经受理并写入记录，工作线程尚未把它推进到运行中。 */
    ACCEPTED,
    /** 工作流节点正在执行。 */
    RUNNING,
    /** 全部节点已经跑完，输出可用。 */
    SUCCEEDED,
    /** 节点或运行控制出错，错误码写在快照里。 */
    FAILED,
    /** 执行在正常结束前被取消。 */
    CANCELLED,
    /** 执行被中断或正在等待通行证，恢复前不再推进节点。 */
    SUSPENDED
}
