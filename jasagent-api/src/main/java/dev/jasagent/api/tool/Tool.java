package dev.jasagent.api.tool;

import java.util.concurrent.CompletionStage;

public interface Tool {
    ToolSpec spec();

    CompletionStage<String> call(ToolCall call);

    default CompletionStage<dev.jasagent.api.workflow.ToolResult> call(ToolCallContext context) {
        throw new UnsupportedOperationException("call(ToolCallContext)");
    }
}
