package dev.jasagent.api.workflow;

import java.util.concurrent.CompletionStage;

public interface ToolInvoker {
    CompletionStage<ToolResult> call(String name, String argumentsJson, String executionId);
}
