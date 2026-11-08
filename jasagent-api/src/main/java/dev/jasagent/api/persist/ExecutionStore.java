package dev.jasagent.api.persist;

import dev.jasagent.api.exec.ExecutionSnapshot;

import java.util.concurrent.CompletionStage;

public interface ExecutionStore {
    CompletionStage<Void> save(ExecutionSnapshot snapshot);

    CompletionStage<ExecutionSnapshot> find(String executionId);

    CompletionStage<Void> saveCheckpoint(Checkpoint checkpoint);

    CompletionStage<Checkpoint> latestCheckpoint(String executionId);
}
