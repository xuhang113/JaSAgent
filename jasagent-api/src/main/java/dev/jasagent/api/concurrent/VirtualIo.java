package dev.jasagent.api.concurrent;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VirtualIo {
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private VirtualIo() {
    }

    public static ExecutorService executor() {
        return EXECUTOR;
    }

    public static <T> CompletionStage<T> supply(Callable<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.call();
            } catch (AgentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new AgentException(ErrorCode.INTERNAL, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), null, ex);
            }
        }, EXECUTOR);
    }
}
