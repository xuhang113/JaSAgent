package dev.jasagent.api.workflow;

import java.util.concurrent.CompletionStage;

public interface PluginHost {
    CompletionStage<Void> invoke(String pluginId, String phase, String executionId, String nodeId);
}
