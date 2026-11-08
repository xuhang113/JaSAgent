package dev.jasagent.interrupt;

import dev.jasagent.api.persist.RunSnapshot;
import dev.jasagent.api.persist.RunStore;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public final class InterruptService {
    private final RunStore store;
    private final ConcurrentHashMap<String, CompletableFuture<?>> running = new ConcurrentHashMap<>();

    public InterruptService(RunStore store) {
        this.store = store;
    }

    public void register(String executionId, CompletableFuture<?> stage) {
        running.put(executionId, stage);
    }

    public RunSnapshot interrupt(String executionId) {
        CompletableFuture<?> stage = running.remove(executionId);
        if (stage != null) {
            stage.cancel(true);
        }
        RunSnapshot current = store.find(executionId);
        RunSnapshot suspended = current.withStatus("SUSPENDED");
        store.save(suspended);
        return suspended;
    }

    public RunSnapshot resume(String executionId, UnaryOperator<RunSnapshot> continuer) {
        RunSnapshot current = store.find(executionId);
        if (!"SUSPENDED".equals(current.status())) {
            return current;
        }
        RunSnapshot continued = continuer.apply(current);
        store.save(continued);
        return continued;
    }
}
