package dev.jasagent.api.workflow;

import java.util.concurrent.CompletionStage;

public interface CheckpointStore {
    CompletionStage<Void> commit(String executionId, String nodeId, String stateJson);
}
