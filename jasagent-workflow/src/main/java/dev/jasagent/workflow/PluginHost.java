package dev.jasagent.workflow;

import java.util.concurrent.CompletionStage;

public interface PluginHost {
    CompletionStage<Void> invoke(PluginCall call);

    record PluginCall(String executionId, String nodeId, String phase, String pluginId) {
    }
}
