package dev.jasagent.api.interrupt;

import dev.jasagent.api.exec.ExecutionSnapshot;

import java.util.concurrent.CompletionStage;

public interface RunControl {
    CompletionStage<ExecutionSnapshot> interrupt(String executionId, String userId);

    CompletionStage<ExecutionSnapshot> resume(String executionId, String userId);
}
