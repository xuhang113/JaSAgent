package dev.jasagent.plugin;

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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 离开集群的外部链接打到 sidecar 的转发入口，回环和本集群地址仍直连。
 * 分块正文和重定向每一跳都按同一条出站判断走。口令不出现在异常说明里。
 */
class HttpLinkFetcherEgressTest {
    /**
     * 放进拒绝正文和数据库配置的假口令。断言失败时也不把它写进说明。
     */
    private static final String SECRET = "link-secret-not-for-logs";
    /**
     * 公网工具地址。分类和转发头都用这一处。
     */
    private static final String PUBLIC = "https://hooks.example.com/tool";

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
     * 确认公网链接的 GET 进 {@code /v1/forward}，分两段写出的正文和内容类型都能读回。
     * 副作用：在回环地址启动临时 sidecar，结束时停止服务。
     */
    @Test
    void publicLinkUsesForwardAndStreamsBody() throws Exception {
        assertEquals("sidecar", EgressRoute.mode(PUBLIC));
        try (Probe sidecar = new Probe()) {
            sidecar.mode = Probe.STREAM;
            SidecarAddress.useForTest(sidecar.base());
            LinkResponse response = new HttpLinkFetcher().fetch(PUBLIC);
            assertEquals("hello rag", response.body());
            assertEquals("text/markdown; charset=utf-8", response.contentType());
            assertFalse(response.body().contains(SECRET));
            assertEquals(1, sidecar.hits.get());
            assertEquals(List.of("/v1/forward"), sidecar.paths);
            assertEquals(List.of("GET"), sidecar.methods);
            assertEquals(List.of(PUBLIC), sidecar.targets);
        }
    }

    /**
     * 确认 sidecar 拒绝时抓取失败，说明里没有拒绝正文，也没有正文里的口令。
     * 副作用：启动临时 sidecar，结束时停止服务。
     */
    @Test
    void rejectionOmitsBodyAndSecret() throws Exception {
        try (Probe sidecar = new Probe()) {
            sidecar.mode = Probe.REJECT;
            SidecarAddress.useForTest(sidecar.base());
            AgentException error = assertThrows(AgentException.class, () -> new HttpLinkFetcher().fetch(PUBLIC));
            assertEquals(ErrorCode.TOOL, error.code());
            assertEquals("link rejected", error.getMessage());
            assertFalse(error.getMessage().contains(SECRET));
            assertFalse(error.getMessage().contains("admitted"));
            assertEquals("/v1/forward", sidecar.paths.get(0));
            assertEquals(PUBLIC, sidecar.targets.get(0));
        }
    }

    /**
     * 确认公网链接的两次重定向都留在转发入口，后一跳的地址仍是公网。
     * 副作用：启动临时 sidecar，结束时停止服务。
     */
    @Test
    void publicRedirectHopsStayOnForward() throws Exception {
        try (Probe sidecar = new Probe()) {
            sidecar.mode = Probe.REDIRECT;
            SidecarAddress.useForTest(sidecar.base());
            LinkResponse response = new HttpLinkFetcher().fetch(PUBLIC);
            assertEquals("final-body", response.body());
            assertEquals(List.of(PUBLIC, "https://hooks.example.com/final"), sidecar.targets);
            assertEquals(List.of("/v1/forward", "/v1/forward"), sidecar.paths);
            assertEquals(List.of("GET", "GET"), sidecar.methods);
        }
    }

    /**
     * 确认回环链接和它的重定向都直连，sidecar 收不到请求，直连请求也不带转发头。
     * 副作用：启动临时 sidecar 和回环服务，结束时停止它们。
     */
    @Test
    void loopbackRedirectStaysDirect() throws Exception {
        try (Probe sidecar = new Probe(); Direct direct = new Direct()) {
            sidecar.mode = Probe.REJECT;
            SidecarAddress.useForTest(sidecar.base());
            String start = direct.url("/start");
            assertEquals("direct", EgressRoute.mode(start));
            LinkResponse response = new HttpLinkFetcher().fetch(start);
            assertEquals("done-body", response.body());
            assertEquals("text/plain", response.contentType());
            assertEquals(0, sidecar.hits.get());
            assertEquals(2, direct.hits.get());
            assertEquals(0, direct.forwardHeaders.get());
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
                "http://jasagent-mysql:3306/",
                "http://jasagent-redis:6379/",
                "http://jasagent-gateway:18721/health",
                "http://nacos:9848/",
                "http://jasagent-gateway.jasagent.svc.cluster.local:18721/health",
                "http://127.0.0.1:9/health",
                "http://localhost:9/health",
                "http://192.0.2.10:8848/nacos/v1/ns/instance",
                "http://192.0.2.10:9848/",
                "http://192.0.2.11:3306/",
                "http://192.0.2.12:6379/",
                "http://192.0.2.13:18721/health");
        HttpLinkFetcher fetcher = new HttpLinkFetcher(Duration.ofMillis(400));
        try (Probe sidecar = new Probe()) {
            sidecar.mode = Probe.REJECT;
            SidecarAddress.useForTest(sidecar.base());
            for (String url : urls) {
                assertEquals("direct", EgressRoute.mode(url));
                AgentException error = assertThrows(AgentException.class, () -> fetcher.fetch(url));
                assertFalse(error.getMessage() != null && error.getMessage().contains(SECRET));
                assertFalse(String.valueOf(error).contains(SECRET));
            }
            assertEquals(0, sidecar.hits.get());
        }
    }

    /**
     * 回环上的链接服务。/start 跳到 /done，并数转发头出现的次数。
     */
    private static final class Direct implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger hits = new AtomicInteger();
        private final AtomicInteger forwardHeaders = new AtomicInteger();

        /**
         * 在回环地址启动服务。
         * 副作用：开始接受连接。
         */
        private Direct() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        /**
         * 拼出这台服务上的绝对地址。
         *
         * @param path 以斜线开头的路径
         * @return 回环上的 http 地址
         */
        private String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        /**
         * /start 返回相对重定向，其它路径返回正文。
         * 副作用：增加计数；发现转发头时另记一次。
         *
         * @param exchange 这一次 HTTP 交换
         */
        private void handle(HttpExchange exchange) throws IOException {
            hits.incrementAndGet();
            if (exchange.getRequestHeaders().getFirst(SidecarAddress.URL_HEADER) != null) {
                forwardHeaders.incrementAndGet();
            }
            exchange.getRequestBody().readAllBytes();
            if ("/start".equals(exchange.getRequestURI().getPath())) {
                exchange.getResponseHeaders().set("Location", "/done");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] body = "done-body".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
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
     * 临时 sidecar。按模式返回分块正文、重定向或拒绝 JSON。
     */
    private static final class Probe implements AutoCloseable {
        /**
         * 把正文拆成两段写出。
         */
        private static final int STREAM = 1;
        /**
         * 第一跳重定向到另一个公网地址，第二跳返回正文。
         */
        private static final int REDIRECT = 2;
        /**
         * 返回含口令的拒绝 JSON。
         */
        private static final int REJECT = 3;

        private final HttpServer server;
        private final AtomicInteger hits = new AtomicInteger();
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private final List<String> targets = new CopyOnWriteArrayList<>();
        private int mode = STREAM;

        /**
         * 在回环地址监听转发路径。
         * 副作用：开始接受连接。
         */
        private Probe() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(SidecarAddress.FORWARD_PATH, this::handle);
            server.start();
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
         * 记录方法和转发头，再按当前模式回答。
         * 副作用：增加计数并写出响应。
         *
         * @param exchange 这一次打到转发入口的交换
         */
        private void handle(HttpExchange exchange) throws IOException {
            int hit = hits.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            methods.add(exchange.getRequestMethod());
            String target = exchange.getRequestHeaders().getFirst(SidecarAddress.URL_HEADER);
            targets.add(target == null ? "" : target);
            exchange.getRequestBody().readAllBytes();
            if (mode == REJECT) {
                byte[] body = ("{\"admitted\":false,\"reason\":\"missing-ticket\",\"echo\":\"" + SECRET + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            if (mode == REDIRECT && hit == 1) {
                exchange.getResponseHeaders().set("Location", "https://hooks.example.com/final");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            if (mode == REDIRECT) {
                byte[] body = "final-body".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/markdown; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.write(" rag".getBytes(StandardCharsets.UTF_8));
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
