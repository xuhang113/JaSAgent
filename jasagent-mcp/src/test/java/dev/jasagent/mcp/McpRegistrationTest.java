package dev.jasagent.mcp;

import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCallContext;
import dev.jasagent.api.workflow.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRegistrationTest {
    @Test
    void toolNameUsesServerAndTool() {
        List<String> errors = new ArrayList<>();
        List<Tool> tools = McpRegistrar.register(List.of(endpoint("demo", List.of("echo"), "ok")), gate(PassDecision.ALLOW), errors);
        assertEquals("mcp_demo_echo", tools.get(0).spec().name());
    }

    @Test
    void callAcquiresPass() {
        CountingGate gate = gate(PassDecision.ALLOW);
        Tool tool = McpRegistrar.register(List.of(endpoint("demo", List.of("echo"), "ok")), gate, new ArrayList<>()).get(0);
        join(tool.call(new ToolCallContext("user-a", "exec-1", "{}")));
        assertEquals(1, gate.calls.get());
        assertEquals("mcp_demo_echo", gate.last.toolName());
    }

    @Test
    void failedServerDoesNotStopRegistration() {
        List<String> errors = new ArrayList<>();
        McpEndpoint down = new McpEndpoint() {
            public String server() { return "down"; }
            public List<String> listTools() { throw new IllegalStateException("offline"); }
            public String call(String tool, String arguments) { return ""; }
        };
        List<Tool> tools = McpRegistrar.register(List.of(down, endpoint("demo", List.of("echo"), "ok")), gate(PassDecision.ALLOW), errors);
        assertTrue(errors.stream().anyMatch(error -> error.contains("down")));
        assertEquals("mcp_demo_echo", tools.get(0).spec().name());
    }

    @Test
    void denyDoesNotCallRemote() {
        AtomicInteger remote = new AtomicInteger();
        McpEndpoint endpoint = new McpEndpoint() {
            public String server() { return "demo"; }
            public List<String> listTools() { return List.of("echo"); }
            public String call(String tool, String arguments) {
                remote.incrementAndGet();
                return "ok";
            }
        };
        Tool tool = McpRegistrar.register(List.of(endpoint), gate(PassDecision.DENY), new ArrayList<>()).get(0);
        join(tool.call(new ToolCallContext("user-a", "exec-1", "{}")));
        assertEquals(0, remote.get());
    }

    @Test
    void pendingDoesNotCallRemote() {
        AtomicInteger remote = new AtomicInteger();
        McpEndpoint endpoint = new McpEndpoint() {
            public String server() { return "demo"; }
            public List<String> listTools() { return List.of("echo"); }
            public String call(String tool, String arguments) {
                remote.incrementAndGet();
                return "ok";
            }
        };
        Tool tool = McpRegistrar.register(List.of(endpoint), gate(PassDecision.PENDING), new ArrayList<>()).get(0);
        ToolResult result = tool.call(new ToolCallContext("user-a", "exec-1", "{}")).toCompletableFuture().join();
        assertEquals(0, remote.get());
        assertFalse(result.success());
        assertEquals("reason", result.text());
    }

    private static McpEndpoint endpoint(String server, List<String> tools, String body) {
        return new McpEndpoint() {
            public String server() { return server; }
            public List<String> listTools() { return tools; }
            public String call(String tool, String arguments) { return body; }
        };
    }

    private static CountingGate gate(PassDecision decision) {
        return new CountingGate(decision);
    }

    private static void join(CompletionStage<?> stage) {
        stage.toCompletableFuture().join();
    }

    private static final class CountingGate implements PassGate {
        private final PassDecision decision;
        private final AtomicInteger calls = new AtomicInteger();
        private PassRequest last;

        private CountingGate(PassDecision decision) {
            this.decision = decision;
        }

        public CompletionStage<Pass> acquire(PassRequest request) {
            calls.incrementAndGet();
            last = request;
            return CompletableFuture.completedFuture(new Pass(decision, "reason"));
        }
    }
}
