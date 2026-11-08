package dev.jasagent.workflow;

import java.util.concurrent.CompletionStage;

public interface ToolCaller {
    CompletionStage<String> call(String executionId, String name, String arguments);
}
