package dev.jasagent.api.tool;

import java.util.concurrent.CompletionStage;

public interface Tool {
    ToolSpec spec();

    default CompletionStage<String> call(ToolCall call) {
        throw new UnsupportedOperationException("call(ToolCall)");
    }

    default CompletionStage<dev.jasagent.api.workflow.ToolResult> call(ToolCallContext context) {
        throw new UnsupportedOperationException("call(ToolCallContext)");
    }
}
