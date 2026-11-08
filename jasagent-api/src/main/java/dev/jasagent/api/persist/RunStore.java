package dev.jasagent.api.persist;

public interface RunStore {
    void save(RunSnapshot snapshot);

    RunSnapshot find(String executionId);
}
