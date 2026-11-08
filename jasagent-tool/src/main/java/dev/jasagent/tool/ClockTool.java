package dev.jasagent.tool;

import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCallContext;
import dev.jasagent.api.tool.ToolSpec;
import dev.jasagent.api.workflow.ToolResult;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ClockTool implements Tool {
    private final Clock clock;

    public ClockTool(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("clock", "返回当前 UTC 时间", true);
    }

    @Override
    public CompletionStage<ToolResult> call(ToolCallContext context) {
        return CompletableFuture.completedFuture(new ToolResult(Instant.now(clock).toString(), true));
    }
}
