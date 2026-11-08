package dev.jasagent.workflow;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface SubagentRunner {
    CompletionStage<String> run(SubagentCall call);

    record SubagentCall(String executionId, String childWorkflowId, String workflowJson, List<String> tasks) {
    }
}
