package dev.jasagent.workflow;

import java.util.List;

public record WorkflowRun(
        String executionId,
        String status,
        String output,
        String errorCode,
        String graphDsl,
        List<String> pluginErrors,
        Boolean evaluationPassed,
        List<String> correctionSources,
        String checkpointSaver,
        String detail,
        String thinking) {
    public WorkflowRun {
        if (thinking == null) {
            thinking = "";
        }
    }
}
