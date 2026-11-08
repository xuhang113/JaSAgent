package dev.jasagent.tool;

import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolSpec;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ClockTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec("clock", "UTC time", true);
    }

    @Override
    public CompletionStage<String> call(ToolCall call) {
        return CompletableFuture.completedFuture(Instant.now().toString());
    }
}
