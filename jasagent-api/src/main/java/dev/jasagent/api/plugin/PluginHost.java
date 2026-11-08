package dev.jasagent.api.plugin;

import dev.jasagent.api.pass.PassApproval;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface PluginHost {
    List<String> ids();

    CompletionStage<PluginResult> invoke(PluginCall call, PluginContext context);

    CompletionStage<PluginResult> completePending(PassApproval approval, PluginContext context);
}
