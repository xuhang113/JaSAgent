package dev.jasagent.api.async;

import java.util.concurrent.CompletionStage;

/**
 * 给已经开始的异步阶段套上某次执行的失败边界。
 * 阶段抛错时结果仍是异常，并带上执行标识；阶段以空结果成功时，按内部错误失败。
 */
public interface AsyncGuard {
    /**
     * 观察给定阶段，返回套上这次执行失败边界之后的阶段。
     * 原阶段异常完成时，返回的阶段也异常完成，且异常带有执行标识；原阶段以空结果成功时，返回的阶段按内部错误失败。
     *
     * @param <T> 阶段产出的结果类型
     * @param stage 已经开始、尚未套上边界的阶段
     * @param executionId 要写进失败异常的执行标识
     * @return 套上边界之后的阶段
     */
    <T> CompletionStage<T> boundary(CompletionStage<T> stage, String executionId);
}
