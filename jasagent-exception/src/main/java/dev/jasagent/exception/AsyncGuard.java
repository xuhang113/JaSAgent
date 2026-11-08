package dev.jasagent.exception;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AsyncGuard {
    public <T> CompletionStage<T> boundary(CompletionStage<T> stage, String executionId) {
        if (stage == null) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.INTERNAL, "异步边界返回了 null", executionId, null));
        }
        return stage.handle((value, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                if (cause instanceof AgentException agent) {
                    throw agent;
                }
                throw new AgentException(ErrorCode.INTERNAL, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), executionId, cause);
            }
            return value;
        });
    }
}
