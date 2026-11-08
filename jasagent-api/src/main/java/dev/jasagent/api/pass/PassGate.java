package dev.jasagent.api.pass;

import dev.jasagent.api.execution.ExecutionStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface PassGate {
    CompletionStage<Pass> acquire(PassRequest request);

    CompletionStage<Pass> decide(PassApproval approval);

    CompletionStage<List<Pass>> pending(String userId, Path userDirectory);

    CompletionStage<ExecutionStatus> executionStatus(String executionId);
}
