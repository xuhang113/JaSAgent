package dev.jasagent.interrupt;

import dev.jasagent.api.persist.RunSnapshot;
import dev.jasagent.api.persist.RunStore;

import java.util.concurrent.ConcurrentHashMap;

final class MemoryRuns implements RunStore {
    private final ConcurrentHashMap<String, RunSnapshot> rows = new ConcurrentHashMap<>();

    @Override
    public void save(RunSnapshot snapshot) {
        rows.put(snapshot.executionId(), snapshot);
    }

    @Override
    public RunSnapshot find(String executionId) {
        return rows.get(executionId);
    }
}
