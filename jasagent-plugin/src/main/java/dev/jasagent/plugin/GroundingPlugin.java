package dev.jasagent.plugin;

import dev.jasagent.api.plugin.HallucinationGuards;
import dev.jasagent.api.plugin.NodePlugin;
import dev.jasagent.api.plugin.PluginContext;
import dev.jasagent.api.plugin.PluginOutcome;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class GroundingPlugin implements NodePlugin {
    @Override
    public String id() {
        return "grounding.default";
    }

    @Override
    public CompletionStage<PluginOutcome> invoke(PluginContext context) {
        if ("afterModel".equals(context.phase())) {
            HallucinationGuards.get().check(context.answer(), context.evidence());
        }
        return CompletableFuture.completedFuture(PluginOutcome.ok());
    }
}
