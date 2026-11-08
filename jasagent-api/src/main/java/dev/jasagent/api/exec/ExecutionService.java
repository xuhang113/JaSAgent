package dev.jasagent.api.exec;

import dev.jasagent.api.event.TraceEvent;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface ExecutionService {
    CompletionStage<ExecutionId> submit(SubmitRequest request);

    CompletionStage<ExecutionSnapshot> get(ExecutionId executionId, String userId);

    CompletionStage<List<ExecutionSnapshot>> list(String userId);

    CompletionStage<ExecutionSnapshot> interrupt(ExecutionId executionId, String userId);

    CompletionStage<ExecutionSnapshot> resume(ExecutionId executionId, String userId);

    CompletionStage<String> logs(ExecutionId executionId, String userId);

    CompletionStage<List<TraceEvent>> events(ExecutionId executionId, String userId);
}
