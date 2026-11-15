package dev.jasagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.llm.egress.EgressRoute;
import dev.jasagent.llm.egress.SidecarAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离开集群的 MCP HTTP 打到 sidecar 的转发入口，回环和本集群地址仍直连。
 * 事件流分两段写出时仍能拼回工具结果。口令不出现在异常说明里。
 */
class HttpMcpSessionEgressTest {
    /**
     * 放进授权头和数据库配置的假口令。断言失败时也不把它写进说明。
     */
    private static final String SECRET = "mcp-bearer-not-for-logs";
    /**
     * 公网 MCP 端点。分类和转发头都用这一处。
     */
    private static final String PUBLIC = "https://mcp.example.com/rpc";

    /**
     * 卸掉测试用的 sidecar 地址和集群主机配置，避免影响后面的用例。
     */
    @AfterEach
    void clearRoute() {
        SidecarAddress.clearTest();
        System.clearProperty("jasagent.nacos.server-addr");
        System.clearProperty("jasagent.jdbc.url");
        System.clearProperty("jasagent.redis.host");
        System.clearProperty("jasagent.redis.url");
        System.clearProperty("jasagent.gateway.origin");
    }

    /**
     * 确认公网端点的握手、通知和工具调用都进 {@code /v1/forward}，工具调用的事件流分块到达后仍能读出文本。
     * 副作用：在回环地址启动临时 sidecar，结束时关掉会话和服务。
     */
    @Test
    void publicEndpointUsesForwardAndStreamsEvent() throws Exception {
        assertEquals("sidecar", EgressRoute.mode(PUBLIC));
        try (Probe sidecar = Probe.forward()) {
            SidecarAddress.useForTest(sidecar.base());
            try (HttpMcpSession session = HttpMcpSession.open(transport(PUBLIC), Duration.ofSeconds(5))) {
                McpProtocol.RemoteResult result = session.callTool("echo", "{\"text\":\"x\"}", "exec-stream");
                assertEquals("streamed", result.text());
                assertFalse(result.error());
                assertFalse(result.text().contains(SECRET));
            }
            assertEquals(4, sidecar.hits.get());
            assertEquals(List.of("/v1/forward", "/v1/forward", "/v1/forward", "/v1/forward"), sidecar.paths);
            assertEquals(List.of(PUBLIC, PUBLIC, PUBLIC, PUBLIC), sidecar.targets);
            assertEquals(List.of("POST", "POST", "POST", "POST"), sidecar.methods);
            assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call"), sidecar.rpcMethods);
            assertEquals("", sidecar.sessions.get(0));
            assertEquals("sid-1", sidecar.sessions.get(1));
            assertEquals("sid-1", sidecar.sessions.get(2));
            assertEquals("sid-1", sidecar.sessions.get(3));
            assertEquals("2024-11-05", sidecar.protocolVersions.get(2));
            assertTrue(sidecar.sawBearer.get());
            assertFalse(String.join("\n", sidecar.targets).contains(SECRET));
        }
    }

    /**
     * 确认 sidecar 拒绝时会话失败，说明里没有拒绝正文，也没有授权头里的口令。
     * 副作用：启动临时 sidecar，握手失败后关掉它。
     */
    @Test
    void rejectionOmitsBodyAndSecret() throws Exception {
        try (Probe sidecar = Probe.rejecting()) {
            SidecarAddress.useForTest(sidecar.base());
            AgentException error = assertThrows(AgentException.class,
                    () -> HttpMcpSession.open(transport(PUBLIC), Duration.ofSeconds(5)));
            assertEquals(ErrorCode.MCP, error.code());
            assertEquals("mcp 请求失败", error.getMessage());
            assertFalse(error.getMessage().contains(SECRET));
            assertFalse(error.getMessage().contains("admitted"));
            assertEquals(1, sidecar.hits.get());
            assertEquals("/v1/forward", sidecar.paths.get(0));
            assertEquals(PUBLIC, sidecar.targets.get(0));
        }
    }

    /**
     * 确认回环上的 MCP 由会话直连，sidecar 收不到请求，直连请求也不带转发头。
     * 副作用：启动临时 sidecar 和回环 MCP，结束时关掉会话和两个服务。
     */
    @Test
    void loopbackStaysDirect() throws Exception {
        try (Probe sidecar = Probe.rejecting(); DirectMcp mcp = new DirectMcp()) {
            SidecarAddress.useForTest(sidecar.base());
            assertEquals("direct", EgressRoute.mode(mcp.endpoint()));
            try (HttpMcpSession session = HttpMcpSession.open(transport(mcp.endpoint()), Duration.ofSeconds(5))) {
                assertEquals("echo", session.tools().get(0).name());
                McpProtocol.RemoteResult result = session.callTool("echo", "{}", "exec-local");
                assertEquals("pong", result.text());
            }
            assertEquals(0, sidecar.hits.get());
            assertTrue(mcp.hits.get() >= 3);
            assertEquals(0, mcp.forwardHeaders.get());
        }
    }

    /**
     * 确认 Nacos、MySQL、Redis、网关和配置里的集群主机都不进 sidecar。配置中的口令不出现在失败说明里。
     * 副作用：启动临时 sidecar，并改写几项系统属性，结束时由清理方法还原。
     */
    @Test
    void clusterAndConfiguredHostsStayDirect() throws Exception {
        System.setProperty("jasagent.nacos.server-addr", "192.0.2.10:8848");
        System.setProperty("jasagent.jdbc.url", "jdbc:mysql://192.0.2.11:3306/jasagent?password=" + SECRET);
        System.setProperty("jasagent.redis.host", "192.0.2.12");
        System.setProperty("jasagent.redis.url", "redis://:" + SECRET + "@192.0.2.12:6379/0");
        System.setProperty("jasagent.gateway.origin", "http://192.0.2.13:18721");
        List<String> urls = List.of(
                "http://jasagent-nacos:8848/mcp",
                "http://jasagent-mysql:3306/mcp",
                "http://jasagent-redis:6379/mcp",
                "http://jasagent-gateway:18721/health",
                "http://nacos:9848/mcp",
                "http://jasagent-gateway.jasagent.svc.cluster.local:18721/health",
                "http://127.0.0.1:9/mcp",
                "http://localhost:9/mcp",
                "http://192.0.2.10:8848/nacos/v1/ns/instance",
                "http://192.0.2.10:9848/",
                "http://192.0.2.11:3306/",
                "http://192.0.2.12:6379/",
                "http://192.0.2.13:18721/health");
        try (Probe sidecar = Probe.rejecting()) {
            SidecarAddress.useForTest(sidecar.base());
            for (String url : urls) {
                assertEquals("direct", EgressRoute.mode(url));
                AgentException error = assertThrows(AgentException.class,
                        () -> HttpMcpSession.open(transport(url), Duration.ofMillis(400)));
                assertFalse(error.getMessage() != null && error.getMessage().contains(SECRET));
                String rendered = String.valueOf(error);
                assertFalse(rendered.contains(SECRET));
            }
            assertEquals(0, sidecar.hits.get());
        }
    }

    /**
     * 带上授权头的 HTTP 传输。授权头的值只存在于请求里。
     *
     * @param url 端点地址
     * @return 每次请求都附带授权头的传输
     */
    private static McpTransport.Http transport(String url) {
        return new McpTransport.Http(URI.create(url), Map.of("Authorization", "Bearer " + SECRET));
    }

    /**
     * 回环上的 MCP。它完成握手并回答 echo，同时数有没有人带上 sidecar 的转发头。
     */
    private static final class DirectMcp implements AutoCloseable {
        private final HttpServer server;
        private final ObjectMapper mapper = new ObjectMapper();
        private final AtomicInteger hits = new AtomicInteger();
        private final AtomicInteger forwardHeaders = new AtomicInteger();

        /**
         * 在回环地址启动 /mcp。
         * 副作用：开始接受连接。
         */
        private DirectMcp() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        /**
         * 这台服务的 MCP 地址。
         *
         * @return 含实际端口的 http 地址
         */
        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }

        /**
         * 按 JSON-RPC 方法回应握手、通知、工具列表和工具调用。
         * 副作用：增加计数；发现转发头时另记一次。
         *
         * @param exchange 这一次 HTTP 交换
         */
        private void handle(HttpExchange exchange) throws IOException {
            hits.incrementAndGet();
            if (exchange.getRequestHeaders().getFirst(SidecarAddress.URL_HEADER) != null) {
                forwardHeaders.incrementAndGet();
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonNode message = mapper.readTree(body);
            if (!message.hasNonNull("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            int id = message.get("id").asInt();
            String method = message.path("method").asText();
            String json;
            if ("initialize".equals(method)) {
                json = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"direct\",\"version\":\"0\"}}}";
            } else if ("tools/list".equals(method)) {
                json = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"Echoes text\",\"inputSchema\":{\"type\":\"object\"}}]}}";
            } else if ("tools/call".equals(method)) {
                json = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"pong\"}],\"isError\":false}}";
            } else {
                json = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"unknown\"}}";
            }
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        /**
         * 立刻停止本机 HTTP 服务。
         * 副作用：不再接受连接。
         */
        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * 临时 sidecar。可以按 MCP 报文回答，也可以一律拒绝。
     */
    private static final class Probe implements AutoCloseable {
        private final HttpServer server;
        private final boolean reject;
        private final ObjectMapper mapper = new ObjectMapper();
        private final AtomicInteger hits = new AtomicInteger();
        private final AtomicBoolean sawBearer = new AtomicBoolean();
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<String> targets = new CopyOnWriteArrayList<>();
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private final List<String> rpcMethods = new CopyOnWriteArrayList<>();
        private final List<String> sessions = new CopyOnWriteArrayList<>();
        private final List<String> protocolVersions = new CopyOnWriteArrayList<>();

        /**
         * 在回环地址监听转发路径。
         * 副作用：开始接受连接。
         *
         * @param reject 为真时一律返回未放行，不解析成工具结果
         */
        private Probe(boolean reject) throws IOException {
            this.reject = reject;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(SidecarAddress.FORWARD_PATH, this::handle);
            server.start();
        }

        /**
         * 打开一个会完成 MCP 握手、并把工具调用写成两段事件流的 sidecar。
         *
         * @return 已经开始监听的探针
         */
        private static Probe forward() throws IOException {
            return new Probe(false);
        }

        /**
         * 打开一个把正文里的口令原样放进拒绝 JSON 的 sidecar，用来确认调用方不会把正文写进异常。
         *
         * @return 已经开始监听的探针
         */
        private static Probe rejecting() throws IOException {
            return new Probe(true);
        }

        /**
         * 测试用的 sidecar 根地址。
         *
         * @return 不含转发路径的回环地址
         */
        private String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /**
         * 记录转发头并按报文回答。工具调用的事件流分成两段写出。
         * 副作用：增加计数。拒绝时写出含口令的正文，调用方不该把它放进异常。
         *
         * @param exchange 这一次打到转发入口的交换
         */
        private void handle(HttpExchange exchange) throws IOException {
            hits.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            String target = exchange.getRequestHeaders().getFirst(SidecarAddress.URL_HEADER);
            targets.add(target == null ? "" : target);
            methods.add(exchange.getRequestMethod());
            String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            sessions.add(session == null ? "" : session);
            String protocol = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            protocolVersions.add(protocol == null ? "" : protocol);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                sawBearer.set(true);
            }
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (reject) {
                byte[] body = ("{\"admitted\":false,\"reason\":\"missing-ticket\",\"echo\":\"" + SECRET + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            JsonNode message = mapper.readTree(request);
            String method = message.path("method").asText("");
            rpcMethods.add(method);
            if (!message.hasNonNull("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            int id = message.get("id").asInt();
            if ("tools/call".equals(method)) {
                writeStream(exchange, id);
                return;
            }
            String json;
            if ("initialize".equals(method)) {
                json = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"side\",\"version\":\"0\"}}}";
                exchange.getResponseHeaders().set("Mcp-Session-Id", "sid-1");
            } else if ("tools/list".equals(method)) {
                json = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"Echoes text\",\"inputSchema\":{\"type\":\"object\"}}]}}";
            } else {
                json = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"unknown\"}}";
            }
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        /**
         * 把工具结果拆成两段事件流写出，中间刷出第一段，模拟分块到达。
         * 副作用：写完后关闭交换。
         *
         * @param exchange 工具调用的 HTTP 交换
         * @param id 请求编号，写进事件里的报文
         */
        private static void writeStream(HttpExchange exchange, int id) throws IOException {
            String prefix = "data: {\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"stre";
            String suffix = "amed\"}],\"isError\":false}}\n\n";
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(prefix.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.write(suffix.getBytes(StandardCharsets.UTF_8));
            out.close();
        }

        /**
         * 立刻停止临时 sidecar。
         * 副作用：不再接受连接。
         */
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
