package dev.jasagent.plugin;

import dev.jasagent.api.plugin.PluginCallEntry;
import dev.jasagent.api.plugin.PluginCallStore;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MemoryPluginCallStore implements PluginCallStore {
    private final List<PluginCallEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public CompletionStage<Void> append(PluginCallEntry entry) {
        entries.add(entry);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<List<PluginCallEntry>> find(String executionId) {
        return CompletableFuture.completedFuture(entries.stream()
                .filter(entry -> executionId.equals(entry.executionId()))
                .toList());
    }
}
