package dev.jasagent.api.plugin;

import java.util.concurrent.CompletionStage;

public interface NodePlugin {
    String id();

    CompletionStage<Void> invoke(PluginContext context);
}
