package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.ArgumentHashes;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.CallStats;
import dev.jasagent.api.plugin.PluginTool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

public final class PluginToolCatalog {
    private final PassGate gate;
    private final Map<String, PluginTool> tools;

    public PluginToolCatalog(PassGate gate, List<PluginTool> tools) {
        Map<String, PluginTool> named = new LinkedHashMap<>();
        for (PluginTool tool : tools) {
            String name = tool.spec().name();
            if (named.put(name, tool) != null) {
                throw new AgentException(ErrorCode.TOOL, "duplicate tool " + name);
            }
        }
        this.gate = gate;
        this.tools = named;
    }

    public static PluginToolCatalog load(PassGate gate) {
        List<PluginTool> found = new ArrayList<>();
        ServiceLoader.load(PluginTool.class, PluginTool.class.getClassLoader()).forEach(found::add);
        return new PluginToolCatalog(gate, found);
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public PluginTool require(String name) {
        PluginTool tool = tools.get(name);
        if (tool == null) {
            throw new AgentException(ErrorCode.TOOL, "unknown tool " + name);
        }
        return tool;
    }

    public CompletionStage<ToolResult> call(ToolCall call) {
        PluginTool tool = tools.get(call.name());
        if (tool == null) {
            return failed(new AgentException(ErrorCode.TOOL, "unknown tool " + call.name()));
        }
        String hash = ArgumentHashes.ofMap(call.arguments());
        PassRequest request = new PassRequest(
                call.userId(),
                call.executionId(),
                call.userDirectory(),
                call.workspaceDirectory(),
                call.name(),
                null,
                hash);
        return gate.acquire(request).thenCompose(pass -> {
            if (pass.decision() == PassDecision.DENY || pass.decision() == PassDecision.PENDING) {
                return CompletableFuture.completedFuture(new ToolResult(call.executionId(), call.name(), false, "", pass));
            }
            if (pass.decision() != PassDecision.ALLOW && pass.decision() != PassDecision.SKIPPED) {
                return failed(new AgentException(ErrorCode.INTERNAL, "decision"));
            }
            return invoke(tool, call, pass);
        });
    }

    private CompletionStage<ToolResult> invoke(PluginTool tool, ToolCall call, Pass pass) {
        CompletionStage<String> stage;
        try {
            stage = tool.call(call);
        } catch (RuntimeException ex) {
            CallStats.load().recordFailure(call.name());
            return failedThrowable(ex);
        }
        return stage.handle((output, error) -> {
            if (error != null) {
                CallStats.load().recordFailure(call.name());
                return failedThrowable(error);
            }
            CallStats.load().recordSuccess(call.name());
            String text = output == null ? "" : output;
            return CompletableFuture.completedFuture(new ToolResult(call.executionId(), call.name(), true, text, pass));
        }).thenCompose(future -> future);
    }

    private static CompletionStage<ToolResult> failedThrowable(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof AgentException agent) {
            return CompletableFuture.failedFuture(agent);
        }
        if (cause instanceof RuntimeException runtime) {
            return CompletableFuture.failedFuture(runtime);
        }
        return failed(new AgentException(ErrorCode.TOOL, message(cause), null, cause));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String message(Throwable cause) {
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static CompletionStage<ToolResult> failed(AgentException error) {
        return CompletableFuture.failedFuture(error);
    }
}
