package dev.jasagent.mcp;

import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCallContext;
import dev.jasagent.api.tool.ToolSpec;
import dev.jasagent.api.workflow.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class McpRegistrar {
    private McpRegistrar() {
    }

    public static List<Tool> register(List<McpEndpoint> endpoints, PassGate gate, List<String> errors) {
        List<Tool> tools = new ArrayList<>();
        for (McpEndpoint endpoint : endpoints) {
            try {
                for (String name : endpoint.listTools()) {
                    tools.add(new RemoteTool(endpoint, name, gate));
                }
            } catch (RuntimeException ex) {
                errors.add(endpoint.server() + ": " + ex.getMessage());
            }
        }
        return tools;
    }

    public static String toolName(String server, String tool) {
        return "mcp_" + sanitize(server) + "_" + sanitize(tool);
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static final class RemoteTool implements Tool {
        private final McpEndpoint endpoint;
        private final String remoteName;
        private final PassGate gate;
        private final String name;

        private RemoteTool(McpEndpoint endpoint, String remoteName, PassGate gate) {
            this.endpoint = endpoint;
            this.remoteName = remoteName;
            this.gate = gate;
            this.name = toolName(endpoint.server(), remoteName);
        }

        public ToolSpec spec() {
            return new ToolSpec(name, "MCP 工具 " + name, false);
        }

        public CompletionStage<ToolResult> call(ToolCallContext context) {
            return gate.acquire(new PassRequest(context.userId(), context.executionId(), name, null, context.argumentsJson()))
                    .thenCompose(pass -> {
                        if (pass.decision() == PassDecision.PENDING) {
                            return CompletableFuture.completedFuture(new ToolResult(pass.reason(), false));
                        }
                        if (pass.decision() == PassDecision.DENY) {
                            return CompletableFuture.completedFuture(new ToolResult("denied", false));
                        }
                        return CompletableFuture.completedFuture(new ToolResult(endpoint.call(remoteName, context.argumentsJson()), true));
                    });
        }
    }
}
