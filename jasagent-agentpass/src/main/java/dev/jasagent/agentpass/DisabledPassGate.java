package dev.jasagent.agentpass;

import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DisabledPassGate implements PassGate {
    @Override
    public CompletionStage<Pass> acquire(PassRequest request) {
        return CompletableFuture.completedFuture(new Pass(PassDecision.SKIPPED, "agentpass-not-enabled"));
    }
}
