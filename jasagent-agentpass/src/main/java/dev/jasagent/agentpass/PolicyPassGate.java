package dev.jasagent.agentpass;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.CompletionStage;

public final class PolicyPassGate implements PassGate {
    private final Function<String, Path> userDirectories;

    public PolicyPassGate(Function<String, Path> userDirectories) {
        if (userDirectories == null) {
            throw new AgentException(ErrorCode.INTERNAL, "pass directory");
        }
        this.userDirectories = userDirectories;
    }

    @Override
    public CompletionStage<Pass> acquire(PassRequest request) {
        return VirtualIo.supply(() -> decide(request));
    }

    private Pass decide(PassRequest request) {
        Path directory = null;
        if (request.userId() != null && !request.userId().isBlank()) {
            directory = userDirectories.apply(request.userId());
        }
        Path policy = directory == null ? null : directory.resolve("auth").resolve("policies.yaml");
        Optional<PassDecision> matched = UserPolicy.read(policy).match(request.toolName(), request.pluginId());
        if (matched.isPresent()) {
            return new Pass(matched.get(), "policy");
        }
        return new Pass(PassDecision.PENDING, "approval-required");
    }
}
