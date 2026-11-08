package dev.jasagent.api.tool;

import java.util.concurrent.CompletionStage;

public interface Tool {
    ToolSpec spec();

    CompletionStage<ToolResult> call(ToolCall call);
}
