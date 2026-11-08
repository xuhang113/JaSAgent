package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.ArgumentHashes;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassApproval;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.AgentPlugin;
import dev.jasagent.api.plugin.CallStats;
import dev.jasagent.api.plugin.PluginCall;
import dev.jasagent.api.plugin.PluginCallEntry;
import dev.jasagent.api.plugin.PluginCallStore;
import dev.jasagent.api.plugin.PluginContext;
import dev.jasagent.api.plugin.PluginFailure;
import dev.jasagent.api.plugin.PluginHost;
import dev.jasagent.api.plugin.PluginResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public final class ServicePluginHost implements PluginHost {
    private final PassGate gate;
    private final PluginCallStore store;
    private final Map<String, AgentPlugin> plugins = new LinkedHashMap<>();
    private final Map<String, PluginCall> suspended = new ConcurrentHashMap<>();

    public ServicePluginHost(PassGate gate, PluginCallStore store, List<AgentPlugin> plugins) {
        for (AgentPlugin plugin : plugins) {
            if (this.plugins.put(plugin.id(), plugin) != null) {
                throw new AgentException(ErrorCode.PLUGIN, "duplicate plugin " + plugin.id());
            }
        }
        this.gate = gate;
        this.store = store;
    }

    public static ServicePluginHost load(PassGate gate, PluginCallStore store) {
        List<AgentPlugin> found = new ArrayList<>();
        ServiceLoader.load(AgentPlugin.class, AgentPlugin.class.getClassLoader()).forEach(found::add);
        return new ServicePluginHost(gate, store, found);
    }

    @Override
    public List<String> ids() {
        return List.copyOf(plugins.keySet());
    }

    @Override
    public CompletionStage<PluginResult> invoke(PluginCall call, PluginContext context) {
        AgentPlugin plugin = plugins.get(call.pluginId());
        if (plugin == null) {
            return failed(new AgentException(ErrorCode.PLUGIN, "unknown plugin " + call.pluginId()));
        }
        String hash = ArgumentHashes.ofPlugin(call.phase().name(), call.arguments(), call.modelOutput());
        PassRequest request = new PassRequest(
                call.userId(),
                call.executionId(),
                call.userDirectory(),
                call.workspaceDirectory(),
                null,
                call.pluginId(),
                hash);
        return gate.acquire(request).thenCompose(pass -> {
            if (pass.decision() == PassDecision.PENDING) {
                suspended.put(pass.passId(), call);
            }
            if (pass.decision() == PassDecision.DENY || pass.decision() == PassDecision.PENDING) {
                return finish(call, hash, pass, false, false, "");
            }
            return run(plugin, call, context, pass, hash);
        });
    }

    @Override
    public CompletionStage<PluginResult> completePending(PassApproval approval, PluginContext context) {
        return gate.decide(approval).thenCompose(pass -> {
            PluginCall stored = suspended.remove(pass.passId());
            if (pass.decision() == PassDecision.DENY) {
                PluginCall basis = stored == null
                        ? new PluginCall(pass.userId(), pass.executionId(), approval.userDirectory(), null, pass.pluginId(), null, "", "", null)
                        : stored;
                return finish(basis, pass.argumentHash(), pass, false, false, "");
            }
            if (pass.decision() != PassDecision.ALLOW) {
                return failed(new AgentException(ErrorCode.INTERNAL, "decision"));
            }
            if (stored == null) {
                return failed(new AgentException(ErrorCode.PLUGIN, "missing suspended call"));
            }
            AgentPlugin plugin = plugins.get(stored.pluginId());
            if (plugin == null) {
                return failed(new AgentException(ErrorCode.PLUGIN, "unknown plugin " + stored.pluginId()));
            }
            return run(plugin, stored, context, pass, pass.argumentHash());
        });
    }

    private CompletionStage<PluginResult> run(AgentPlugin plugin, PluginCall call, PluginContext context, Pass pass, String hash) {
        return plugin.invoke(call, context).handle((output, error) -> {
            if (error == null) {
                CallStats.load().recordSuccess(call.pluginId());
                return finish(call, hash, pass, true, false, output == null ? "" : output);
            }
            CallStats.load().recordFailure(call.pluginId());
            Throwable cause = unwrap(error);
            if (call.failure() == PluginFailure.FAIL) {
                return finish(call, hash, pass, false, true, message(cause)).thenCompose(ignored -> {
                    CompletableFuture<PluginResult> failed = new CompletableFuture<>();
                    failed.completeExceptionally(asPlugin(cause));
                    return failed;
                });
            }
            return finish(call, hash, pass, false, true, message(cause));
        }).thenCompose(stage -> stage);
    }

    private CompletionStage<PluginResult> finish(
            PluginCall call,
            String hash,
            Pass pass,
            boolean executed,
            boolean failed,
            String output) {
        PluginResult result = new PluginResult(call.executionId(), call.pluginId(), executed, failed, output, pass);
        return store.append(new PluginCallEntry(
                call.executionId(),
                call.userId(),
                call.pluginId(),
                call.phase() == null ? "" : call.phase().name(),
                hash,
                pass == null ? null : pass.passId(),
                pass == null ? null : pass.decision(),
                pass == null ? null : pass.reason(),
                executed,
                failed,
                output)).thenApply(ignored -> result);
    }

    private static AgentException asPlugin(Throwable cause) {
        if (cause instanceof AgentException agent && agent.code() == ErrorCode.PLUGIN) {
            return agent;
        }
        return new AgentException(ErrorCode.PLUGIN, message(cause), null, cause);
    }

    private static String message(Throwable cause) {
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletionStage<T> failed(AgentException error) {
        return CompletableFuture.failedFuture(error);
    }
}
