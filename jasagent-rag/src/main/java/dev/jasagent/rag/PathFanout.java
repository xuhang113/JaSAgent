package dev.jasagent.rag;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class PathFanout {
    private final Executor executor;

    public PathFanout(Executor executor) {
        this.executor = executor;
    }

    public <T> CompletionStage<List<T>> all(List<Callable<T>> tasks) {
        List<CompletableFuture<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (RuntimeException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new AgentException(ErrorCode.INTERNAL, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), null, ex);
                }
            }, executor));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<T> values = new ArrayList<>();
            for (CompletableFuture<T> future : futures) {
                values.add(future.join());
            }
            return values;
        });
    }
}
