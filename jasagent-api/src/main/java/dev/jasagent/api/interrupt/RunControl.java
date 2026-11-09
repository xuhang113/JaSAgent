package dev.jasagent.api.interrupt;

import dev.jasagent.api.exec.ExecutionSnapshot;

import java.util.concurrent.CompletionStage;

/**
 * 在执行过程中取消正在跑的节点，或从挂起的检查点继续。
 * 这是对正在运行的执行做中止和恢复，图上预先写好的停顿节点另走工作流。
 */
public interface RunControl {
    /**
     * 中止一次正在运行的执行。
     * 状态为运行中时取消当前节点并改成挂起，同时留下中止事件；其他状态原样返回当前快照。另一名用户来操作时以权限错误失败。
     *
     * @param executionId 要中止的执行
     * @param userId 发起中止的用户，须是该执行的所有者
     * @return 完成后得到中止之后的执行快照
     */
    CompletionStage<ExecutionSnapshot> interrupt(String executionId, String userId);

    /**
     * 用原来的执行标识，从挂起的检查点继续。
     * 状态为已挂起时从下一个节点再跑，并留下恢复事件；其他状态原样返回当前快照，且不新开根追踪。
     *
     * @param executionId 要恢复的执行
     * @param userId 发起恢复的用户，须是该执行的所有者
     * @return 完成后得到恢复之后的执行快照
     */
    CompletionStage<ExecutionSnapshot> resume(String executionId, String userId);
}
