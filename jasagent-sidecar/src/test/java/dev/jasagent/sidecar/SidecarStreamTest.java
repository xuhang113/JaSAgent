package dev.jasagent.sidecar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实端口核对 {@code /v1/forward} 会边收边发。整包缓冲时，对端在看到第一块之前不会放行下一块，用例会超时。
 * 不连接 18741，也不占用网关和 agent 的端口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "server.port=0",
        "jasagent.sidecar.agentpass-base-url=http://127.0.0.1:9",
        "jasagent.sidecar.agentpass-timeout-millis=200",
        "jasagent.sidecar.upstream-timeout-millis=5000",
        "jasagent.sidecar.llm-allowlist=127.0.0.1"
})
class SidecarStreamTest {
    /**
     * 已经在用的端口。随机端口撞上就失败，不去改那些进程。
     */
    private static final Set<Integer> RESERVED = Set.of(18721, 18731, 18732, 18741, 18751, 8080, 18761);

    @LocalServerPort
    private int port;

    /**
     * 响应按块送回。调用方读到第一段之后，上游才写剩余部分。请求正文同样按流交给上游，不先收成完整数组。
     */
    @Test
    void responseBodyIsForwardedBeforeItEnds() throws Exception {
        assertFalse(RESERVED.contains(port));
        CountDownLatch clientSawFirst = new CountDownLatch(1);
        HttpServer upstream = server();
        upstream.createContext("/stream", exchange -> respond(exchange, clientSawFirst));
        upstream.start();
        int upstreamPort = upstream.getAddress().getPort();
        assertFalse(RESERVED.contains(upstreamPort));
        try {
            String target = "http://127.0.0.1:" + upstreamPort + "/stream";
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + EgressPaths.FORWARD))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(8))
                    .header(EgressHeaders.URL, target)
                    .POST(HttpRequest.BodyPublishers.ofString("ping"))
                    .build();
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            byte[] first = response.body().readNBytes(4);
            assertEquals("one\n", new String(first, StandardCharsets.UTF_8));
            clientSawFirst.countDown();
            String rest = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("two\n", rest);
            response.body().close();
            client.close();
        } finally {
            stop(upstream);
        }
    }

    /**
     * 先吐出第一段响应并刷出，等调用方确认后再写完。
     *
     * @param exchange 上游这次交换
     * @param clientSawFirst 调用方已经看到第一段时放开
     */
    private static void respond(HttpExchange exchange, CountDownLatch clientSawFirst) throws java.io.IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write("one\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        try {
            if (!clientSawFirst.await(5, TimeUnit.SECONDS)) {
                exchange.close();
                return;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            exchange.close();
            return;
        }
        out.write("two\n".getBytes(StandardCharsets.UTF_8));
        out.close();
    }

    /**
     * 在回环地址上开一个临时端口。线程是守护线程，测试结束不会被它拖住。
     *
     * @return 尚未启动的服务器
     */
    private static HttpServer server() throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "sidecar-stream-upstream");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(pool);
        return server;
    }

    /**
     * 停掉临时上游并关闭它的线程池。
     *
     * @param server 测试自己启动的服务器
     */
    private static void stop(HttpServer server) {
        server.stop(0);
        if (server.getExecutor() instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
    }
}
