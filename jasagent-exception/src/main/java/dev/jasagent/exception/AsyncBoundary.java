package dev.jasagent.exception;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AsyncBoundary {
    private AsyncBoundary() {
    }

    public static <T> CompletionStage<T> guard(String executionId, Callable<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                T value = call.call();
                if (value == null) {
                    throw new AgentException(ErrorCode.INTERNAL, "异步边界不能返回空", executionId, null);
                }
                return value;
            } catch (AgentException ex) {
                if (ex.executionId() == null || ex.executionId().isBlank()) {
                    throw new AgentException(ex.code(), ex.getMessage(), executionId, ex.getCause() == null ? ex : ex.getCause());
                }
                throw ex;
            } catch (Exception ex) {
                throw new AgentException(
                        ErrorCode.INTERNAL,
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                        executionId,
                        ex);
            }
        }, VirtualIo.executor());
    }
}
