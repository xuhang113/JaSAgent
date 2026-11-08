package dev.jasagent.api.schedule;

import dev.jasagent.api.task.WorkItem;

import java.util.concurrent.CompletionStage;

public interface SubAgentRunner {
    CompletionStage<Void> run(WorkItem item);
}
