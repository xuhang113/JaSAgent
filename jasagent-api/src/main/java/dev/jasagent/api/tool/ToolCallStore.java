package dev.jasagent.api.tool;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface ToolCallStore {
    CompletionStage<Void> append(ToolCallEntry entry);

    CompletionStage<List<ToolCallEntry>> find(String executionId);
}
