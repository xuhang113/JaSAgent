package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.NodePlugin;
import dev.jasagent.api.plugin.PluginContext;
import dev.jasagent.api.plugin.PluginOutcome;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class GatedPluginRunner {
    public CompletionStage<PluginOutcome> run(NodePlugin plugin, PluginContext context, PassGate gate, boolean failOnError) {
        PassRequest request = new PassRequest(null, context.executionId(), null, plugin.id(), context.phase());
        return gate.acquire(request).thenCompose(pass -> {
            if (pass.decision() == PassDecision.DENY) {
                return CompletableFuture.completedFuture(new PluginOutcome(false, pass.reason()));
            }
            return plugin.invoke(context).handle((outcome, error) -> {
                if (error != null) {
                    if (failOnError) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        throw new AgentException(ErrorCode.PLUGIN, cause.getMessage(), context.executionId(), cause);
                    }
                    return new PluginOutcome(true, "continued");
                }
                return outcome;
            });
        });
    }
}
