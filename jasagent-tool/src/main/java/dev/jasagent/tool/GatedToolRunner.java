package dev.jasagent.tool;

import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassLog;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCallContext;
import dev.jasagent.api.workflow.ToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class GatedToolRunner {
    public CompletionStage<ToolResult> run(Tool tool, ToolCallContext context, PassGate gate, PassLog log) {
        if (tool.spec().internal()) {
            return tool.call(context);
        }
        PassRequest request = new PassRequest(
                context.userId(),
                context.executionId(),
                tool.spec().name(),
                null,
                hash(context.argumentsJson()));
        return gate.acquire(request).thenCompose(pass -> {
            if (log != null) {
                log.record(context.executionId(), "tool", tool.spec().name(), pass);
            }
            if (pass.decision() == PassDecision.PENDING) {
                return CompletableFuture.completedFuture(new ToolResult(pass.reason(), false));
            }
            if (pass.decision() == PassDecision.DENY) {
                return CompletableFuture.completedFuture(new ToolResult("denied", false));
            }
            return tool.call(context);
        });
    }

    private static String hash(String arguments) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((arguments == null ? "" : arguments).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return "";
        }
    }
}
