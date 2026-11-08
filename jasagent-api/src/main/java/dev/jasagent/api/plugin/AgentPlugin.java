package dev.jasagent.api.plugin;

import java.util.concurrent.CompletionStage;

public interface AgentPlugin {
    String id();

    CompletionStage<String> invoke(PluginCall call, PluginContext context);
}
