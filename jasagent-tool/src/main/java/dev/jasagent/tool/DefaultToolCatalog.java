package dev.jasagent.tool;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.ArgumentHashes;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassApproval;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.CallStats;
import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolCallEntry;
import dev.jasagent.api.tool.ToolCallStore;
import dev.jasagent.api.tool.ToolCatalog;
import dev.jasagent.api.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public final class DefaultToolCatalog implements ToolCatalog {
    private final PassGate gate;
    private final ToolCallStore store;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, ToolCall> suspended = new ConcurrentHashMap<>();

    public DefaultToolCatalog(PassGate gate, ToolCallStore store, List<Tool> tools) {
        for (Tool tool : tools) {
            String name = tool.spec().name();
            if (this.tools.put(name, tool) != null) {
                throw new AgentException(ErrorCode.TOOL, "duplicate tool " + name);
            }
        }
        this.gate = gate;
        this.store = store;
    }

    @Override
    public CompletionStage<ToolResult> call(ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return failed(new AgentException(ErrorCode.TOOL, "unknown tool " + call.name()));
        }
        if (tool.spec().internal() || "clock".equals(call.name())) {
            return tool.call(call).thenCompose(output -> finish(call, null, null, true, output));
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
            if (pass.decision() == PassDecision.PENDING) {
                suspended.put(pass.passId(), call);
            }
            if (pass.decision() == PassDecision.DENY || pass.decision() == PassDecision.PENDING) {
                return finish(call, hash, pass, false, "");
            }
            return invoke(tool, call, hash, pass);
        });
    }

    @Override
    public CompletionStage<ToolResult> completePending(PassApproval approval) {
        return gate.decide(approval).thenCompose(pass -> {
            ToolCall stored = suspended.remove(pass.passId());
            if (pass.decision() == PassDecision.DENY) {
                ToolCall basis = stored == null
                        ? new ToolCall(pass.userId(), pass.executionId(), approval.userDirectory(), null, pass.toolName(), Map.of())
                        : stored;
                return finish(basis, pass.argumentHash(), pass, false, "");
            }
            if (pass.decision() != PassDecision.ALLOW) {
                return failed(new AgentException(ErrorCode.INTERNAL, "decision"));
            }
            if (stored == null) {
                return failed(new AgentException(ErrorCode.TOOL, "missing suspended call"));
            }
            Tool tool = tools.get(stored.name());
            if (tool == null) {
                return failed(new AgentException(ErrorCode.TOOL, "unknown tool " + stored.name()));
            }
            return invoke(tool, stored, pass.argumentHash(), pass);
        });
    }

    private CompletionStage<ToolResult> invoke(Tool tool, ToolCall call, String hash, Pass pass) {
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
            return finish(call, hash, pass, true, output);
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

    private CompletionStage<ToolResult> finish(ToolCall call, String hash, Pass pass, boolean executed, String output) {
        ToolResult result = new ToolResult(call.executionId(), call.name(), executed, output, pass);
        return store.append(new ToolCallEntry(
                call.executionId(),
                call.userId(),
                call.name(),
                hash,
                pass == null ? null : pass.passId(),
                pass == null ? null : pass.decision(),
                pass == null ? null : pass.reason(),
                executed,
                output)).thenApply(ignored -> result);
    }

    private static <T> CompletionStage<T> failed(AgentException error) {
        return CompletableFuture.failedFuture(error);
    }
}
