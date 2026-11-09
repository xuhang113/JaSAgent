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

/**
 * 用本机假服务器和标准输入输出脚本覆盖 MCP 工具的注册、通行证和调用结果。
 * 连不上的服务器只被记下来，同名工具则会让整次打开失败。
 */
class McpClientTest {
    /**
     * 确认假服务器的 echo 注册成 mcp_demo_echo，说明是 Echoes text，并且不是内部工具。
     * 副作用：启动本机假服务器，结束时关掉会话和服务。
     */
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

    /**
     * 确认申请通行证时远程 tools/call 还没发出，申请里是注册名、用户和执行，没有插件标识。
     * 副作用：申请之后向假服务器发送一次调用。
     */
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

    /**
     * 确认通行证里的参数哈希等于对同一段 JSON 独立计算的 SHA-256 十六进制。
     * 副作用：向假服务器发送一次调用。
     */
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

    /**
     * 确认通行证被拒绝时不发送 tools/call，结果也不算成功。
     * 副作用：打开会话，但不增加假服务器的工具调用计数。
     */
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

    /**
     * 确认允许调用时按远程原名 echo 发送，并返回服务器给出的 pong。
     * 副作用：假服务器记下最后一次工具名。
     */
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

    /**
     * 确认通行证被跳过时仍然发送一次 tools/call。
     * 副作用：假服务器的工具调用计数变为 1。
     */
    @Test
    void skippedSendsToolsCall() throws Exception {
        try (FakeMcp server = new FakeMcp();
             McpToolProvider provider = openDemo(server, gate(PassDecision.SKIPPED))) {
            join(provider.tools().get(0).call(new ToolCallContext("user-a", "exec-a", "{}")));
            assertEquals(1, server.toolsCalls.get());
        }
    }

    /**
     * 确认一台已经没有进程监听的服务器不会挡住另一台，活着的 echo 仍注册成 mcp_up_echo。
     * 副作用：启动一台假服务器，并尝试连接一个已关闭端口。
     */
    @Test
    void unreachableServerDoesNotBlockOpen() throws Exception {
        try (Pair pair = upAndGone()) {
            assertTrue(pair.provider.tools().stream().anyMatch(tool -> "mcp_up_echo".equals(tool.spec().name())));
        }
    }

    /**
     * 确认连不上的服务器出现在不可达列表里，并且不会注册带它前缀的工具。
     * 副作用：启动一台假服务器，并尝试连接一个已关闭端口。
     */
    @Test
    void unreachableServerRegistersNoTools() throws Exception {
        try (Pair pair = upAndGone()) {
            assertTrue(pair.provider.unreachableServerIds().contains("gone"));
            assertTrue(pair.provider.tools().stream().noneMatch(tool -> tool.spec().name().startsWith("mcp_gone_")));
        }
    }

    /**
     * 确认两台服务器使用同一个标识时打开失败，错误码是 MCP。
     * 副作用：启动两台假服务器；打开失败后不留下提供者。
     */
    @Test
    void duplicateNameFailsOpenWithMcp() throws Exception {
        try (FakeMcp first = new FakeMcp(); FakeMcp second = new FakeMcp()) {
            AgentException error = assertThrows(AgentException.class, () -> join(McpToolProvider.open(List.of(
                    McpServerConfig.http("demo", first.endpoint(), Duration.ofSeconds(5)),
                    McpServerConfig.http("demo", second.endpoint(), Duration.ofSeconds(5))), gate(PassDecision.ALLOW))));
            assertEquals(ErrorCode.MCP, error.code());
        }
    }

    /**
     * 确认服务器返回 JSON-RPC 错误时，工具调用失败且错误码是 MCP。
     * 副作用：把假服务器的下一次调用改成返回错误报文。
     */
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

    /**
     * 确认服务器还按住响应时，返回的阶段尚未完成；放开后才得到 pong。
     * 副作用：用闩拦住假服务器的一次 tools/call，断言未完成后再放开。
     */
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

    /**
     * 确认申请通行证发生在虚拟线程上，调用结果仍是 pong。
     * 副作用：在测试门上记下申请线程是不是虚拟线程。
     */
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

    /**
     * 确认模块描述和主源码没有引用通行证实现所在的包，也没有引用被排除的外部框架。
     * 副作用：只读取构建描述和主源码，不修改这些文件。
     */
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

    /**
     * 确认允许调用时，标准输入输出子进程的 echo 注册成 mcp_pipe_echo，并回传参数里的文本。
     * 副作用：写下临时脚本、拉起子进程，结束时删除脚本并关闭会话。
     */
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

    /**
     * 确认服务器把结果标成错误时，调用返回文本 bad 且成功标志为假，不抛异常。
     * 副作用：把假服务器的下一次调用改成返回错误结果。
     */
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

    /**
     * 确认工具定义保留注册名、远程说明，以及含 object 的输入参数模式。
     * 副作用：打开假服务器并在结束时关闭会话。
     */
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

    /**
     * 把假服务器按 demo 这个标识打开，单次等待 5 秒。
     *
     * @param server 已经启动的假 MCP 服务
     * @param gate 工具调用前使用的测试门
     * @return 已经完成握手的工具提供者
     */
    private static McpToolProvider openDemo(FakeMcp server, RecordingGate gate) {
        return join(McpToolProvider.open(List.of(McpServerConfig.http("demo", server.endpoint(), Duration.ofSeconds(5))), gate));
    }

    /**
     * 同时打开一台端口已经关闭的服务器和一台还活着的假服务器。
     * 副作用：启动活着的那台假服务器；返回后由调用方关闭这一对。
     *
     * @return 活着的服务器和已经打开的提供者
     */
    private static Pair upAndGone() throws Exception {
        FakeMcp up = new FakeMcp();
        McpToolProvider provider = join(McpToolProvider.open(List.of(
                McpServerConfig.http("gone", closedHttp(), Duration.ofSeconds(2)),
                McpServerConfig.http("up", up.endpoint(), Duration.ofSeconds(5))), gate(PassDecision.ALLOW)));
        return new Pair(up, provider);
    }

    /**
     * 借一个回环端口再马上放开，得到一个当前没有服务监听的地址。
     * 副作用：打开并立即关闭一个 ServerSocket。
     *
     * @return 指向该端口 /mcp 的地址
     */
    private static URI closedHttp() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/mcp");
        }
    }

    /**
     * 做一个会记下申请的通行证门，并设好它返回的决定。
     *
     * @param decision 每次申请都返回的决定
     * @return 还没接到申请的测试门
     */
    private static RecordingGate gate(PassDecision decision) {
        RecordingGate gate = new RecordingGate();
        gate.decision = decision;
        return gate;
    }

    /**
     * 对文本的 UTF-8 字节独立计算 SHA-256，用来对照工具提交的参数哈希。
     *
     * @param value 要摘要的参数 JSON
     * @return 小写十六进制摘要
     */
    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 在 10 秒内取回异步结果，并把包装异常还原成原来的运行时异常。
     * 副作用：等待被中断时重新置上当前线程的中断标记。
     *
     * @param <T> 阶段的结果类型
     * @param stage 要等待的异步阶段
     * @return 阶段的正常结果
     */
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

    /**
     * 记下最后一次通行证申请、申请时是否在虚拟线程，以及当时远程工具已经被调用了几次。
     */
    private static final class RecordingGate implements PassGate {
        private PassDecision decision;
        private PassRequest last;
        private boolean virtual;
        private int callsAtAcquire = -1;
        private FakeMcp server;

        /**
         * 保存这次申请，并记下当前线程和假服务器已经收到的调用次数。
         * 副作用：改写最后一次申请、虚拟线程标志和申请时的调用计数。
         *
         * @param request 工具带来的申请
         * @return 已完成的通行证，原因是决定名的小写形式
         */
        @Override
        public CompletionStage<Pass> acquire(PassRequest request) {
            last = request;
            virtual = Thread.currentThread().isVirtual();
            callsAtAcquire = server == null ? 0 : server.toolsCalls.get();
            return java.util.concurrent.CompletableFuture.completedFuture(new Pass(decision, decision.name().toLowerCase()));
        }
    }

    /**
     * 把还活着的假服务器和已经打开的提供者放在一起关闭。
     */
    private static final class Pair implements AutoCloseable {
        private final FakeMcp server;
        private final McpToolProvider provider;

        /**
         * 记住要一起关闭的假服务器和提供者。
         *
         * @param server 还活着的假服务器
         * @param provider 已经打开的工具提供者
         */
        private Pair(FakeMcp server, McpToolProvider provider) {
            this.server = server;
            this.provider = provider;
        }

        /**
         * 先关工具提供者，再停掉假服务器。
         * 副作用：关闭会话并停止本机 HTTP 服务。
         */
        @Override
        public void close() {
            provider.close();
            server.close();
        }
    }

    /**
     * 本机 HTTP 假 MCP 服务器。
     * 它能完成握手、列出 echo，并按开关返回正常文本、错误结果或 JSON-RPC 错误。
     */
    static final class FakeMcp implements AutoCloseable {
        final AtomicInteger toolsCalls = new AtomicInteger();
        final AtomicReference<String> lastTool = new AtomicReference<>();
        volatile boolean failCall;
        volatile boolean errorResult;
        volatile CountDownLatch hold;
        private final HttpServer server;
        private final ObjectMapper mapper = new ObjectMapper();

        /**
         * 在回环地址启动 HTTP 服务，并挂上 /mcp。
         * 副作用：开始接受连接。
         */
        FakeMcp() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        /**
         * 返回这台假服务器的 MCP 地址。
         *
         * @return 含实际端口的 http 地址，路径是 /mcp
         */
        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        }

        /**
         * 立刻停止本机 HTTP 服务。
         * 副作用：不再接受连接。
         */
        @Override
        public void close() {
            server.stop(0);
        }

        /**
         * 按 JSON-RPC 方法回应握手、工具列表和工具调用。
         * 副作用：工具调用时增加计数并记下工具名；闩存在时先等待。失败开关写错误报文，错误结果开关返回 bad。
         *
         * @param exchange 这一次 HTTP 交换
         */
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

        /**
         * 把一段 JSON 写回这次交换并关闭它。
         * 副作用：设置内容类型、发送状态和正文，然后关闭交换。
         *
         * @param exchange 要回应的 HTTP 交换
         * @param status HTTP 状态码
         * @param json 响应正文
         */
        private static void respond(HttpExchange exchange, int status, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
