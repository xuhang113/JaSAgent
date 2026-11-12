package dev.jasagent.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用本机临时端口核对 sidecar 发给 agentpass 的路径，以及同一张证只能转发一次。
 * 临时端口若撞上已有的 18721、18731、18732、18741 或 18751，立刻停掉并失败，不去改那些进程。
 */
class TicketWireTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<Integer> RESERVED = Set.of(18721, 18731, 18732, 18741, 18751);

    /**
     * 有效证转发到本地上游；同一张证第二次 agentpass 报告已用，上游不再收到请求。
     * 校验是 {@code POST /v1/tickets/verify}，核销是 {@code POST /v1/tickets/redeem}。
     */
    @Test
    void validTicketForwardsAndSecondUseIsRejected() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<byte[]> seen = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> ticketHeader = new AtomicReference<>();
        HttpServer upstream = server();
        upstream.createContext("/hook", exchange -> {
            seen.set(exchange.getRequestBody().readAllBytes());
            hits.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            ticketHeader.set(exchange.getRequestHeaders().getFirst(EgressHeaders.TICKET));
            byte[] body = "seen".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        AtomicInteger redeems = new AtomicInteger();
        AtomicReference<String> verifyTarget = new AtomicReference<>();
        AtomicReference<String> verifyLine = new AtomicReference<>();
        AtomicReference<String> redeemLine = new AtomicReference<>();
        HttpServer pass = server();
        pass.createContext(EgressPaths.VERIFY, exchange -> {
            verifyLine.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            assertNull(exchange.getRequestURI().getQuery());
            JsonNode node = JSON.readTree(exchange.getRequestBody().readAllBytes());
            verifyTarget.set(node.path("target").asText());
            boolean used = redeems.get() > 0;
            write(exchange, 200, verdictJson(used));
        });
        pass.createContext(EgressPaths.REDEEM, exchange -> {
            redeemLine.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            int count = redeems.incrementAndGet();
            if (count == 1) {
                write(exchange, 200, redeemJson(true));
            } else {
                write(exchange, 409, redeemJson(false));
            }
        });
        try {
            upstream.start();
            pass.start();
            assertEphemeral(upstream.getAddress().getPort());
            assertEphemeral(pass.getAddress().getPort());
            String url = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/hook";
            try (HttpTicketOffice office = new HttpTicketOffice("http://127.0.0.1:" + pass.getAddress().getPort(), 2000);
                 JdkUpstreamClient client = new JdkUpstreamClient(2000)) {
                EgressService service = new EgressService(LlmAllowlist.parse(""), office, client);
                ForwardRequest request = new ForwardRequest("POST", url, Map.of(
                        "Authorization", List.of("Bearer test-token"),
                        EgressHeaders.TICKET, List.of("ticket-once"),
                        "Content-Type", List.of("application/json")),
                        "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                        "ticket-once", "", "", "", "", "");
                EgressOutcome first = service.forward(request);
                assertTrue(first.forwarded());
                assertEquals(200, first.status());
                assertEquals("seen", new String(first.body(), StandardCharsets.UTF_8));
                assertEquals(1, hits.get());
                assertEquals(url, verifyTarget.get());
                assertEquals("POST " + EgressPaths.VERIFY, verifyLine.get());
                assertEquals("POST " + EgressPaths.REDEEM, redeemLine.get());
                assertEquals("Bearer test-token", authorization.get());
                assertNull(ticketHeader.get());
                assertEquals("{\"a\":1}", new String(seen.get(), StandardCharsets.UTF_8));
                EgressOutcome second = service.forward(request);
                assertFalse(second.forwarded());
                assertEquals(403, second.status());
                assertTrue(new String(second.body(), StandardCharsets.UTF_8).contains("\"reason\":\"used\""));
                assertEquals(1, hits.get());
            }
        } finally {
            stop(upstream);
            stop(pass);
        }
    }

    /**
     * 上游返回重定向时原样交回，不继续请求 Location 里的另一台主机。
     */
    @Test
    void redirectIsReturnedAndNotFollowed() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer upstream = server();
        upstream.createContext("/go", exchange -> {
            hits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "http://example.com/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        try {
            upstream.start();
            assertEphemeral(upstream.getAddress().getPort());
            URI uri = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/go");
            try (JdkUpstreamClient client = new JdkUpstreamClient(2000)) {
                UpstreamResponse response = client.exchange("GET", uri, Map.of(), new byte[0]);
                assertTrue(response.reached());
                assertEquals(302, response.status());
                assertEquals(1, hits.get());
            }
        } finally {
            stop(upstream);
        }
    }

    /**
     * 在回环地址上开一个临时端口，线程设成守护线程，避免测试结束后还占着进程。
     *
     * @return 尚未启动的服务器
     */
    private static HttpServer server() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "sidecar-test-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(pool);
        return server;
    }

    /**
     * 写出一段 JSON。调用方已经读完请求体。
     *
     * @param exchange 当前交换
     * @param status HTTP 状态码
     * @param json 不含证明文的正文
     */
    private static void write(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * 停掉临时服务器并关闭它的线程池。
     *
     * @param server 测试自己启动的服务器，可以是空
     */
    private static void stop(HttpServer server) {
        if (server == null) {
            return;
        }
        try {
            server.stop(0);
        } catch (RuntimeException ignored) {
            // 还没 start 时忽略，测试收尾仍然要关掉线程池。
        }
        if (server.getExecutor() instanceof ExecutorService pool) {
            pool.shutdownNow();
        }
    }

    /**
     * 临时端口不能是已经在用的网关、agent、通行证或检索端口。
     *
     * @param port 刚绑定到的端口
     */
    private static void assertEphemeral(int port) {
        assertFalse(RESERVED.contains(port));
    }

    /**
     * 造一份校验报告。已用时原因码用 agentpass 的 {@code replay}，sidecar 对外应写成 {@code used}。
     *
     * @param used 这张证是否已经核销
     * @return JSON 正文
     */
    private static String verdictJson(boolean used) {
        return "{\"proofValid\":true,\"used\":" + used + ",\"expired\":false,\"userMatch\":true,"
                + "\"targetMatch\":true,\"tenantMatch\":true,\"sessionMatch\":true,\"executionMatch\":true,"
                + "\"accepted\":" + (!used) + ",\"reason\":" + (used ? "\"replay\"" : "null") + "}";
    }

    /**
     * 造一份核销响应。成功时也把 {@code used} 标成真，和通行证服务的实际响应一样。
     *
     * @param redeemed 这次是否核销成功
     * @return JSON 正文
     */
    private static String redeemJson(boolean redeemed) {
        return "{\"redeemed\":" + redeemed + ",\"proofValid\":true,\"used\":true,\"expired\":false,"
                + "\"userMatch\":true,\"targetMatch\":true,\"tenantMatch\":true,\"sessionMatch\":true,"
                + "\"executionMatch\":true,\"accepted\":" + redeemed + ",\"reason\":\""
                + (redeemed ? "redeemed" : "replay") + "\"}";
    }
}
