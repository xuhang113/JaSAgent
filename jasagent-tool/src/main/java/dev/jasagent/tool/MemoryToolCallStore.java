package dev.jasagent.tool;

import dev.jasagent.api.tool.ToolCallEntry;
import dev.jasagent.api.tool.ToolCallStore;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MemoryToolCallStore implements ToolCallStore {
    private final List<ToolCallEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public CompletionStage<Void> append(ToolCallEntry entry) {
        entries.add(entry);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<List<ToolCallEntry>> find(String executionId) {
        return CompletableFuture.completedFuture(entries.stream()
                .filter(entry -> executionId.equals(entry.executionId()))
                .toList());
    }
}
