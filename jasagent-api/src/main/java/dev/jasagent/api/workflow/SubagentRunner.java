package dev.jasagent.api.workflow;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface SubagentRunner {
    CompletionStage<List<String>> run(String executionId, String childWorkflowJson, List<String> parts);
}
