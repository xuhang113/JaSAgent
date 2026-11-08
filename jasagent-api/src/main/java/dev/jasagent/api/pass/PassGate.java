package dev.jasagent.api.pass;

import dev.jasagent.api.execution.ExecutionStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface PassGate {
    CompletionStage<Pass> acquire(PassRequest request);

    default CompletionStage<Pass> decide(PassApproval approval) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("decide"));
    }

    default CompletionStage<List<Pass>> pending(String userId, Path userDirectory) {
        return CompletableFuture.completedFuture(List.of());
    }

    default CompletionStage<ExecutionStatus> executionStatus(String executionId) {
        return CompletableFuture.completedFuture(null);
    }
}
