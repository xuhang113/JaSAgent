package dev.jasagent.api.tool;

import dev.jasagent.api.workflow.ToolResult;

import java.util.concurrent.CompletionStage;

public interface Tool {
    ToolSpec spec();

    CompletionStage<ToolResult> call(ToolCallContext context);
}
