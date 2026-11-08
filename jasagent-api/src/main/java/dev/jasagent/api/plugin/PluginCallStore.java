package dev.jasagent.api.plugin;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface PluginCallStore {
    CompletionStage<Void> append(PluginCallEntry entry);

    CompletionStage<List<PluginCallEntry>> find(String executionId);
}
