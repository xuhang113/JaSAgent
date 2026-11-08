package dev.jasagent.exception;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

public final class TaskConsumer {
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
