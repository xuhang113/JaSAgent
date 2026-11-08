package dev.jasagent.api.schedule;

import dev.jasagent.api.task.WorkItem;

import java.util.concurrent.CompletionStage;

public interface AgentScheduler {
    CompletionStage<Void> enqueue(WorkItem item);

    CompletionStage<ScheduleBatch> scheduleOnce();
}
