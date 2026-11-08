package dev.jasagent.core;

import dev.jasagent.api.workflow.WorkflowResult;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface WorkflowEngine {
    CompletionStage<WorkflowResult> execute(AgentTask task);
}
