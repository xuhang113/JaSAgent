package dev.jasagent.api.exec;

import dev.jasagent.api.event.TraceEvent;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 执行生命周期服务。提交工作流，并按用户核对后查询、中断、恢复和读取日志与追踪。
 */
public interface ExecutionService {
    /**
     * 受理一次工作流执行并返回新建的执行编号。
     *
     * @param request 用户、会话、工作区、工作流、输入和关联键
     * @return 完成后给出执行编号的阶段
     */
    CompletionStage<ExecutionId> submit(SubmitRequest request);

    /**
     * 读取一条属于指定用户的执行快照。
     *
     * @param executionId 执行编号
     * @param userId 调用方用户登录名，必须与快照里的用户一致
     * @return 完成后给出当前快照的阶段；执行不存在或不属于该用户时失败
     */
    CompletionStage<ExecutionSnapshot> get(ExecutionId executionId, String userId);

    /**
     * 列出指定用户的执行快照。
     *
     * @param userId 用户登录名
     * @return 完成后给出该用户执行列表的阶段
     */
    CompletionStage<List<ExecutionSnapshot>> list(String userId);

    /**
     * 中断一条正在运行的执行，使它停在挂起状态；已经不在运行中的执行保持原状态。
     *
     * @param executionId 执行编号
     * @param userId 调用方用户登录名，必须拥有这条执行
     * @return 完成后给出中断后快照的阶段
     */
    CompletionStage<ExecutionSnapshot> interrupt(ExecutionId executionId, String userId);

    /**
     * 从挂起位置继续一条执行。
     *
     * @param executionId 执行编号
     * @param userId 调用方用户登录名，必须拥有这条执行
     * @return 完成后给出恢复后快照的阶段；执行不是挂起状态时保持原快照
     */
    CompletionStage<ExecutionSnapshot> resume(ExecutionId executionId, String userId);

    /**
     * 读取一条执行已经积累的日志文本。
     *
     * @param executionId 执行编号
     * @param userId 调用方用户登录名，必须拥有这条执行
     * @return 完成后给出日志文本的阶段
     */
    CompletionStage<String> logs(ExecutionId executionId, String userId);

    /**
     * 读取一条执行已经发出的追踪事件。
     *
     * @param executionId 执行编号
     * @param userId 调用方用户登录名，必须拥有这条执行
     * @return 完成后给出追踪事件列表的阶段
     */
    CompletionStage<List<TraceEvent>> events(ExecutionId executionId, String userId);
}
