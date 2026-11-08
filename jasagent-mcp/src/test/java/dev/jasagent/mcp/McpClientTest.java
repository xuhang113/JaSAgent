package dev.jasagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ToolDefinition;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCallContext;
import dev.jasagent.api.workflow.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientTest {
    @Test
    void demoEchoRegistersAsMcpDemoEcho() throws Exception {
        try (FakeMcp server = new FakeMcp();
             McpToolProvider provider = openDemo(server, gate(PassDecision.ALLOW))) {
            assertEquals(1, provider.tools().size());
            Tool tool = provider.tools().get(0);
            assertEquals("mcp_demo_echo", tool.spec().name());
            assertEquals("Echoes text", tool.spec().description());
            assertFalse(tool.spec().internal());
        }
    }

    @Test
    void acquireRunsBeforeRemoteCall() throws Exception {
        try (FakeMcp server = new FakeMcp()) {
            RecordingGate gate = gate(PassDecision.ALLOW);
            gate.server = server;
            try (McpToolProvider provider = openDemo(server, gate)) {
                join(provider.tools().get(0).call(new ToolCallContext("user-a", "exec-a", "{\"k\":\"v\"}")));
                assertEquals(0, gate.callsAtAcquire);
                assertEquals("mcp_demo_echo", gate.last.toolName());
                assertEquals("user-a", gate.last.userId());
                assertEquals("exec-a", gate.last.executionId());
                assertNull(gate.last.pluginId());
            }
        }
    }

    @Test
    void argumentsHashIsSha256Hex() throws Exception {
        String json = "{\"k\":\"v\"}";
        String expected = sha256(json);
        try (FakeMcp server = new FakeMcp()) {
            RecordingGate gate = gate(PassDecision.ALLOW);
            try (McpToolProvider provider = openDemo(server, gate)) {
                join(provider.tools().get(0).call(new ToolCallContext("user-a", "exec-a", json)));
                assertEquals(expected, gate.last.argumentsHash());
            }
        }
    }

    @Test
    void denyDoesNotSendToolsCall() throws Exception {
        try (FakeMcp server = new FakeMcp();
             McpToolProvider provider = openDemo(server, gate(PassDecision.DENY))) {
            ToolResult result = join(provider.tools().get(0).call(new ToolCallContext("user-a", "exec-a", "{}")));
            assertNotNull(result);
            assertEquals(0, server.toolsCalls.get());
            assertFalse(result.success());
        }
    }

    @Test
    void allowSendsOriginalNameAndReturnsText() throws Exception {
        try (FakeMcp server = new FakeMcp();
             McpToolProvider provider = openDemo(server, gate(PassDecision.ALLOW))) {
            ToolResult result = join(provider.tools().get(0).call(new ToolCallContext("user-a", "exec-a", "{\"text\":\"pong\"}")));
            assertEquals("echo", server.lastTool.get());
            assertEquals("pong", result.text());
            assertTrue(result.success());
        }
    }

    @Test
    void skippedSendsToolsCall() throws Exception {
        try (FakeMcp server = new FakeMcp();
             McpToolProvider provider = openDemo(server, gate(PassDecision.SKIPPED))) {
            join(provider.tools().get(0).call(new ToolCallContext("user-a", "exec-a", "{}")));
            assertEquals(1, server.toolsCalls.get());
        }
    }

    @Test
    void unreachableServerDoesNotBlockOpen() throws Exception {
        try (Pair pair = upAndGone()) {
            assertTrue(pair.provider.tools().stream().anyMatch(tool -> "mcp_up_echo".equals(tool.spec().name())));
        }
    }

    @Test
    void unreachableServerRegistersNoTools() throws Exception {
        try (Pair pair = upAndGone()) {
            assertTrue(pair.provider.unreachableServerIds().contains("gone"));
            assertTrue(pair.provider.tools().stream().noneMatch(tool -> tool.spec().name().startsWith("mcp_gone_")));
        }
    }

    @Test
    void duplicateNameFailsOpenWithMcp() throws Exception {
        try (FakeMcp first = new FakeMcp(); FakeMcp second = new FakeMcp()) {
            AgentException error = assertThrows(AgentException.class, () -> join(McpToolProvider.open(List.of(
                    McpServerConfig.http("demo", first.endpoint(), Duration.ofSeconds(5)),
                    McpServerConfig.http("demo", second.endpoint(), Duration.ofSeconds(5))), gate(PassDecision.ALLOW))));
            assertEquals(ErrorCode.MCP, error.code());
        }
    }

    @Test
    void jsonRpcErrorFailsWithMcp() throws Exception {
        try (FakeMcp server = new FakeMcp();
             McpToolProvider provider = openDemo(server, gate(PassDecision.ALLOW))) {
            server.failCall = true;
            AgentException error = assertThrows(AgentException.class,
                    () -> join(provider.tools().get(0).call(new ToolCallContext("user-a", "exec-a", "{}"))));
            assertNotNull(error);
            assertEquals(ErrorCode.MCP, error.code());
        }
    }

    @Test
    void callReturnsIncompleteStage() throws Exception {
        try (FakeMcp server = new FakeMcp()) {
            server.hold = new CountDownLatch(1);
            try (McpToolProvider provider = join(McpToolProvider.open(List.of(
                    McpServerConfig.http("demo", server.endpoint(), Duration.ofSeconds(2))), gate(PassDecision.ALLOW)))) {
                CompletionStage<ToolResult> stage = provider.tools().get(0).call(new ToolCallContext("user-a", "exec-a", "{}"));
                assertFalse(stage.toCompletableFuture().isDone());
                server.hold.countDown();
                assertEquals("pong", join(stage).text());
            }
        }
    }

    @Test
    void acquireRunsOnVirtualThread() throws Exception {
        try (FakeMcp server = new FakeMcp()) {
            RecordingGate gate = gate(PassDecision.ALLOW);
            try (McpToolProvider provider = openDemo(server, gate)) {
                ToolResult result = join(provider.tools().get(0).call(new ToolCallContext("user-a", "exec-a", "{}")));
                assertTrue(gate.virtual);
                assertEquals("pong", result.text());
            }
        }
    }

    @Test
    void sourcesDoNotReferenceAgentpassOrSpring() throws Exception {
        Path module = Path.of("").toAbsolutePath();
        String pom = Files.readString(module.resolve("pom.xml"));
        assertFalse(pom.contains("jasagent-agentpass"));
        assertFalse(pom.toLowerCase().contains("springframework"));
        assertFalse(pom.toLowerCase().contains("spring-ai"));
        Path src = module.resolve("src/main/java");
        assertTrue(Files.isDirectory(src));
        try (Stream<Path> walk = Files.walk(src)) {
            List<Path> files = walk.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(files.isEmpty());
            for (Path file : files) {
                String text = Files.readString(file);
                assertFalse(text.contains("dev.jasagent.agentpass"), file.toString());
                assertFalse(text.contains("org.springframework"), file.toString());
                assertFalse(text.contains("spring-ai"), file.toString());
            }
        }
    }

    @Test
    void stdioAllowReturnsRemoteText() throws Exception {
        Path script = Files.createTempFile("jasagent-mcp", ".py");
        Files.writeString(script, """
                import json, sys

                def send(obj):
                    sys.stdout.write(json.dumps(obj) + "\\n")
                    sys.stdout.flush()

                while True:
                    line = sys.stdin.readline()
                    if line == "":
                        break
                    msg = json.loads(line)
                    if "id" not in msg:
                        continue
                    method = msg.get("method")
                    ident = msg["id"]
                    if method == "initialize":
                        send({"jsonrpc": "2.0", "id": ident, "result": {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}}, "serverInfo": {"name": "pipe", "version": "0"}}})
                    elif method == "tools/list":
                        send({"jsonrpc": "2.0", "id": ident, "result": {"tools": [{"name": "echo", "description": "stdio echo", "inputSchema": {"type": "object"}}]}})
                    elif method == "tools/call":
                        args = msg.get("params", {}).get("arguments") or {}
                        text = args.get("text", "") if isinstance(args, dict) else ""
                        send({"jsonrpc": "2.0", "id": ident, "result": {"content": [{"type": "text", "text": text}], "isError": False}})
                    else:
                        send({"jsonrpc": "2.0", "id": ident, "error": {"code": -32601, "message": "unknown"}})
                """);
        try (McpToolProvider provider = join(McpToolProvider.open(List.of(
                McpServerConfig.stdio("pipe", List.of("python3", "-u", script.toString()), Duration.ofSeconds(5))),
                gate(PassDecision.ALLOW)))) {
            assertEquals("mcp_pipe_echo", provider.tools().get(0).spec().name());
            ToolResult result = join(provider.tools().get(0).call(new ToolCallContext("user-a", "exec-a", "{\"text\":\"stdio-pong\"}")));
            assertEquals("stdio-pong", result.text());
            assertTrue(result.success());
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    void remoteErrorFlagReturnsTextWithoutThrowing() throws Exception {
        try (FakeMcp server = new FakeMcp();
             McpToolProvider provider = openDemo(server, gate(PassDecision.ALLOW))) {
            server.errorResult = true;
            ToolResult result = join(provider.tools().get(0).call(new ToolCallContext("user-a", "exec-a", "{}")));
            assertEquals("bad", result.text());
            assertFalse(result.success());
        }
    }

    @Test
    void definitionKeepsInputSchema() throws Exception {
        try (FakeMcp server = new FakeMcp();
             McpToolProvider provider = openDemo(server, gate(PassDecision.ALLOW))) {
            assertEquals(1, provider.definitions().size());
            ToolDefinition definition = provider.definitions().get(0);
            assertEquals("mcp_demo_echo", definition.name());
            assertEquals("Echoes text", definition.description());
            assertTrue(definition.parametersJson().contains("object"));
        }
    }

    private static McpToolProvider openDemo(FakeMcp server, RecordingGate gate) {
        return join(McpToolProvider.open(List.of(McpServerConfig.http("demo", server.endpoint(), Duration.ofSeconds(5))), gate));
    }

    private static Pair upAndGone() throws Exception {
        FakeMcp up = new FakeMcp();
        McpToolProvider provider = join(McpToolProvider.open(List.of(
                McpServerConfig.http("gone", closedHttp(), Duration.ofSeconds(2)),
                McpServerConfig.http("up", up.endpoint(), Duration.ofSeconds(5))), gate(PassDecision.ALLOW)));
        return new Pair(up, provider);
    }

    private static URI closedHttp() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/mcp");
        }
    }

    private static RecordingGate gate(PassDecision decision) {
        RecordingGate gate = new RecordingGate();
        gate.decision = decision;
        return gate;
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class RecordingGate implements PassGate {
        private PassDecision decision;
        private PassRequest last;
        private boolean virtual;
        private int callsAtAcquire = -1;
        private FakeMcp server;

        @Override
        public CompletionStage<Pass> acquire(PassRequest request) {
            last = request;
            virtual = Thread.currentThread().isVirtual();
            callsAtAcquire = server == null ? 0 : server.toolsCalls.get();
            return java.util.concurrent.CompletableFuture.completedFuture(new Pass(decision, decision.name().toLowerCase()));
        }
    }

    private static final class Pair implements AutoCloseable {
        private final FakeMcp server;
        private final McpToolProvider provider;

        private Pair(FakeMcp server, McpToolProvider provider) {
            this.server = server;
            this.provider = provider;
        }

        @Override
        public void close() {
            provider.close();
            server.close();
        }
    }

    static final class FakeMcp implements AutoCloseable {
        final AtomicInteger toolsCalls = new AtomicInteger();
        final AtomicReference<String> lastTool = new AtomicReference<>();
        volatile boolean failCall;
        volatile boolean errorResult;
        volatile CountDownLatch hold;
        private final HttpServer server;
        private final ObjectMapper mapper = new ObjectMapper();

        FakeMcp() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonNode message;
            try {
                message = mapper.readTree(body);
            } catch (Exception ex) {
                respond(exchange, 400, "{}");
                return;
            }
            if (!message.hasNonNull("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            String method = message.path("method").asText();
            int id = message.get("id").asInt();
            if ("tools/call".equals(method)) {
                toolsCalls.incrementAndGet();
                lastTool.set(message.path("params").path("name").asText());
                if (hold != null) {
                    try {
                        hold.await(4, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (failCall) {
                    respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32000,\"message\":\"工具爆炸\"}}");
                    return;
                }
                String text = errorResult ? "bad" : "pong";
                respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],\"isError\":" + errorResult + "}}");
                return;
            }
            if ("initialize".equals(method)) {
                respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fake\",\"version\":\"0\"}}}");
                return;
            }
            if ("tools/list".equals(method)) {
                respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"Echoes text\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}}]}}");
                return;
            }
            respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"unknown\"}}");
        }

        private static void respond(HttpExchange exchange, int status, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
