package dev.jasagent.api.persist;

import java.util.concurrent.CompletionStage;

public interface ExecutionStore {
    CompletionStage<Void> insertExecution(ExecutionRecord record);

    CompletionStage<ExecutionRecord> findExecution(String executionId);

    CompletionStage<Void> updateExecution(String executionId, String status, String errorCode, String outputText);
}
