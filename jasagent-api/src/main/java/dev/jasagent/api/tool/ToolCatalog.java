package dev.jasagent.api.tool;

import dev.jasagent.api.pass.PassApproval;

import java.util.concurrent.CompletionStage;

public interface ToolCatalog {
    CompletionStage<ToolResult> call(ToolCall call);

    CompletionStage<ToolResult> completePending(PassApproval approval);
}
