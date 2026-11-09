package dev.jasagent.api.persist;

import dev.jasagent.api.exec.ExecutionSnapshot;

import java.util.concurrent.CompletionStage;

/**
 * 保存执行快照和节点检查点。
 * 写入与读取都返回阶段，阶段完成前不能假定已经落盘或已经读到。
 */
public interface ExecutionStore {
    /**
     * 保存一条执行快照。
     * 阶段完成表示这份快照已经按执行标识留下。
     *
     * @param snapshot 要保存的执行快照
     * @return 保存完成后结束的阶段
     */
    CompletionStage<Void> save(ExecutionSnapshot snapshot);

    /**
     * 按执行标识读回先前保存的执行快照。
     *
     * @param executionId 要读取的执行
     * @return 完成后得到该执行快照的阶段
     */
    CompletionStage<ExecutionSnapshot> find(String executionId);

    /**
     * 保存一条节点检查点，供之后从该节点继续。
     *
     * @param checkpoint 执行、节点、步号和当时的状态
     * @return 保存完成后结束的阶段
     */
    CompletionStage<Void> saveCheckpoint(Checkpoint checkpoint);

    /**
     * 读取某次执行最新的一条检查点。
     *
     * @param executionId 要读取的执行
     * @return 完成后得到最新检查点的阶段
     */
    CompletionStage<Checkpoint> latestCheckpoint(String executionId);
}
