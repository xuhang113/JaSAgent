package dev.jasagent.api.workflow;

import java.util.concurrent.CompletionStage;

public interface WorkflowEngine {
    CompletionStage<WorkflowResult> execute(WorkflowRequest request);
}
