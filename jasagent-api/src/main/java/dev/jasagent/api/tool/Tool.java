package dev.jasagent.api.tool;

import java.util.concurrent.CompletionStage;

public interface Tool {
    ToolSpec spec();

    CompletionStage<String> call(ToolCall call);
}
