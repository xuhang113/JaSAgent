package dev.jasagent.core;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class SchedulingExecutionService {
    private final AgentScheduler scheduler;
    private final ExecutionLog log;

    public SchedulingExecutionService(AgentScheduler scheduler, ExecutionLog log) {
        this.scheduler = scheduler;
        this.log = log;
    }

    public CompletionStage<String> submit(SubmitCommand command) {
        String executionId = UUID.randomUUID().toString();
        String sessionId = command.sessionId() == null || command.sessionId().isBlank() ? executionId : command.sessionId();
        log.accepted(executionId, command.userId(), sessionId, command.workspace());
        scheduler.enqueue(new WorkItem(executionId, executionId, sessionId, null, command.input(), "{}", command.input()));
        scheduler.scheduleOnce();
        return CompletableFuture.completedFuture(executionId);
    }
}
