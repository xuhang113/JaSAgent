package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.plugin.AgentPlugin;
import dev.jasagent.api.plugin.PluginCall;
import dev.jasagent.api.plugin.PluginContext;
import dev.jasagent.api.plugin.PluginPhase;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class GroundingDefaultPlugin implements AgentPlugin {
    @Override
    public String id() {
        return "grounding.default";
    }

    @Override
    public CompletionStage<String> invoke(PluginCall call, PluginContext context) {
        if (call.phase() != PluginPhase.afterModel) {
            return CompletableFuture.completedFuture("");
        }
        if (context == null || context.guard() == null) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.PLUGIN, "hallucination guard missing"));
        }
        return context.guard().check(call.executionId(), call.modelOutput()).thenApply(ignored -> "grounding.default");
    }
}
