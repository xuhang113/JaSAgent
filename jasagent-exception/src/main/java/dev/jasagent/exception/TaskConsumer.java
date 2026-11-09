package dev.jasagent.exception;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * 执行单个任务动作，失败时通知失败槽并让后续任务仍能继续被消费。
 */
public final class TaskConsumer {
    /**
     * 在异步边界里运行任务动作。动作抛错时把异常补上执行标识、通知失败槽，并返回失败结局；失败槽自己再抛错也不会中断这次消费。
     *
     * @param executionId 这次执行的标识，会补进缺少执行标识的异常
     * @param taskId 交给失败槽的任务标识
     * @param action 真正要执行的任务动作
     * @param sink 动作失败时接收失败通知的槽
     * @return 动作正常结束时为已完成，否则为已失败
     */
    public CompletionStage<ConsumeOutcome> consume(
            String executionId,
            String taskId,
            Callable<?> action,
            TaskFailureSink sink) {
        return AsyncBoundary.guard(executionId, () -> {
            action.call();
            return ConsumeOutcome.COMPLETED;
        }).exceptionally(error -> {
            AgentException agent = unwrap(error, executionId);
            try {
                sink.markFailed(executionId, taskId, agent);
            } catch (RuntimeException ignored) {
                // 标记失败本身不再中断进程，后续请求仍可进入。
            }
            return ConsumeOutcome.FAILED;
        });
    }

    /**
     * 剥掉异步包装，把任意异常收成带执行标识的业务异常；已有执行标识的业务异常保持原样。
     *
     * @param error 阶段给出的异常，可能包在完成异常里
     * @param executionId 当前任务所属执行
     * @return 适合交给失败槽的业务异常
     */
    private static AgentException unwrap(Throwable error, String executionId) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof AgentException agent) {
            if (agent.executionId() == null || agent.executionId().isBlank()) {
                return new AgentException(agent.code(), agent.getMessage(), executionId, agent.getCause());
            }
            return agent;
        }
        return new AgentException(
                ErrorCode.INTERNAL,
                current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage(),
                executionId,
                current);
    }
}
