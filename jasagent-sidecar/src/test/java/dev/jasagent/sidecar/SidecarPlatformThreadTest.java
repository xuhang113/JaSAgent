package dev.jasagent.sidecar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实 Tomcat 核对 {@code /v1/forward} 在等待和抄写时不占用操作系统上的平台工作线程。
 * 平台线程数由 {@code jasagent.sidecar.tomcat-platform-workers} 固定，{@code server.tomcat.threads.max} 故意留得更高。
 * 不连接 18741，也不占用网关和 agent 的端口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "server.port=0",
        "server.tomcat.threads.max=64",
        "spring.threads.virtual.enabled=false",
        "jasagent.sidecar.tomcat-platform-workers=2",
        "jasagent.sidecar.agentpass-timeout-millis=4000",
        "jasagent.sidecar.upstream-timeout-millis=8000",
        "jasagent.sidecar.llm-allowlist=127.0.0.1"
})
class SidecarPlatformThreadTest {
    /**
     * 已经在用的端口。随机端口撞上就失败。
     */
    private static final Set<Integer> RESERVED = Set.of(18721, 18731, 18732, 18741, 18751, 8080, 18761);
    /**
     * 同时进行的长流式转发次数。必须多于平台工作线程，也必须少于 {@code server.tomcat.threads.max}。
     */
    private static final int STREAMS = 6;
    /**
     * 验收里故意压低的 Tomcat 平台工作线程数。
     */
    private static final int PLATFORM_CAP = 2;
    /**
     * 配置里的 {@code server.tomcat.threads.max}。它高于并发流，不能被当成这次压低的依据。
     */
    private static final int TOMCAT_MAX = 64;

    /**
     * 为真时，校验接口先停住，用来观察等待通行证的窗口。
     */
    private static final AtomicBoolean delayPass = new AtomicBoolean();
    /**
     * 校验接口已经读到请求。测试在这之后才采样平台线程。
     */
    private static final AtomicReference<CountDownLatch> passEntered = new AtomicReference<>(new CountDownLatch(1));
    /**
     * 放行被停住的校验接口。
     */
    private static final AtomicReference<CountDownLatch> passRelease = new AtomicReference<>(new CountDownLatch(1));
    /**
     * 为真时，上游先停住再写响应头，用来观察等待上游的窗口。
     */
    private static final AtomicBoolean delayHeaders = new AtomicBoolean();
    /**
     * 上游已经读到请求。测试在这之后才采样平台线程。
     */
    private static final AtomicReference<CountDownLatch> headersEntered = new AtomicReference<>(new CountDownLatch(1));
    /**
     * 放行被停住的上游响应头。
     */
    private static final AtomicReference<CountDownLatch> headersRelease = new AtomicReference<>(new CountDownLatch(1));
    /**
     * 放行慢流的第二块。
     */
    private static final AtomicReference<CountDownLatch> releaseSecond = new AtomicReference<>(new CountDownLatch(1));
    /**
     * 放行慢流的第三块。
     */
    private static final AtomicReference<CountDownLatch> releaseThird = new AtomicReference<>(new CountDownLatch(1));

    /**
     * 本机临时上游。只在测试进程里监听，不占用约定端口。
     */
    private static final HttpServer upstream = server();
    /**
     * 本机临时通行证。只返回固定的无效报告，不保存证明文。
     */
    private static final HttpServer pass = server();

    static {
        upstream.createContext("/slow", SidecarPlatformThreadTest::slow);
        upstream.createContext("/fast", SidecarPlatformThreadTest::fast);
        upstream.createContext("/hold", SidecarPlatformThreadTest::hold);
        pass.createContext(EgressPaths.VERIFY, SidecarPlatformThreadTest::verify);
        pass.createContext(EgressPaths.REDEEM, exchange -> write(exchange, 500, "{\"redeemed\":false}"));
        upstream.start();
        pass.start();
    }

    @LocalServerPort
    private int port;

    @Value("${server.tomcat.threads.max}")
    private int tomcatMax;

    @Value("${jasagent.sidecar.tomcat-platform-workers}")
    private int platformCap;

    /**
     * 把通行证服务指到本测试的临时端口。上下文启动前就会读到。
     *
     * @param registry Spring 测试用的属性注册表
     */
    @DynamicPropertySource
    static void agentpass(DynamicPropertyRegistry registry) {
        registry.add("jasagent.sidecar.agentpass-base-url",
                () -> "http://127.0.0.1:" + pass.getAddress().getPort());
    }

    /**
     * 停掉临时上游和通行证。重复调用没有额外作用。
     */
    @AfterAll
    static void stopServers() {
        stop(upstream);
        stop(pass);
    }

    /**
     * 平台线程只有 {@link #PLATFORM_CAP} 条时，仍能同时维持更多路长流式转发。
     * 第一块在上游结束前到达，之后还会继续出块；这段时间里 {@code /health} 和新的转发在超时内返回。
     * 压低的是名字以平台前缀开头的操作系统线程，不是 {@code server.tomcat.threads.max}。
     */
    @Test
    void longStreamsExceedPlatformWorkers() throws Exception {
        assertFalse(RESERVED.contains(port));
        assertFalse(RESERVED.contains(upstream.getAddress().getPort()));
        assertEquals(TOMCAT_MAX, tomcatMax);
        assertEquals(PLATFORM_CAP, platformCap);
        assertTrue(tomcatMax > STREAMS);
        assertTrue(STREAMS > platformCap);
        releaseSecond.set(new CountDownLatch(1));
        releaseThird.set(new CountDownLatch(1));
        try (HttpClient client = client()) {
            warmup(client);
            int baseline = osTasks();
            List<StreamCall> calls = new ArrayList<>();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> tasks = new ArrayList<>();
                for (int i = 0; i < STREAMS; i++) {
                    StreamCall call = new StreamCall();
                    calls.add(call);
                    tasks.add(pool.submit(() -> readSlow(client, call)));
                }
                for (StreamCall call : calls) {
                    assertTrue(call.gotFirst.await(5, TimeUnit.SECONDS), "first chunk did not arrive before upstream finished");
                    assertNull(call.error);
                    assertEquals("a1\n", call.first);
                }
                int during = osTasks();
                List<Thread> workers = platformWorkers();
                assertEquals(platformCap, workers.size());
                for (Thread worker : workers) {
                    assertFalse(worker.isVirtual(), worker.getName());
                }
                for (int sample = 0; sample < 4; sample++) {
                    assertNoSidecarOnPlatformThreads();
                    Thread.sleep(40);
                }
                HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, health.statusCode());
                assertTrue(health.body().contains("\"status\":\"up\""));
                HttpResponse<String> again = client.send(forward(fastUrl()).timeout(Duration.ofSeconds(2)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, again.statusCode());
                assertEquals("ok", again.body());
                releaseSecond.get().countDown();
                for (StreamCall call : calls) {
                    assertTrue(call.gotSecond.await(5, TimeUnit.SECONDS), "stream stopped emitting");
                    assertNull(call.error);
                    assertEquals("a2\n", call.second);
                }
                releaseThird.get().countDown();
                for (Future<?> task : tasks) {
                    task.get(5, TimeUnit.SECONDS);
                }
                for (StreamCall call : calls) {
                    assertNull(call.error);
                    assertEquals("a3\n", call.third);
                }
                long defaultExec = Thread.getAllStackTraces().keySet().stream()
                        .filter(thread -> !thread.isVirtual() && thread.getName().contains("-exec-"))
                        .count();
                assertEquals(0, defaultExec, "Tomcat 默认的 exec 平台线程还在");
                System.out.printf("ATDD-021 workers=%d tomcatMax=%d streams=%d osDelta=%d pinned=0%n",
                        workers.size(), tomcatMax, STREAMS, during - baseline);
                // 同进程里的测试客户端会占 HttpClient 平台线程，/proc 增量不能当成 sidecar 的平台线程。
                // 独立进程的验收脚本再量 sidecar 自己的操作系统线程。
            }
        }
    }

    /**
     * 等待 agentpass 时，Tomcat 平台工作线程的栈里没有通行证调用。
     */
    @Test
    void waitingForAgentpassDoesNotHoldPlatformWorkers() throws Exception {
        delayPass.set(true);
        passEntered.set(new CountDownLatch(1));
        passRelease.set(new CountDownLatch(1));
        try (HttpClient client = client(); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HttpResponse<String>> future = pool.submit(() -> client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + EgressPaths.FORWARD))
                            .version(HttpClient.Version.HTTP_1_1)
                            .timeout(Duration.ofSeconds(6))
                            .header(EgressHeaders.URL, "http://localhost/hook")
                            .header(EgressHeaders.TICKET, "sample-ticket")
                            .POST(HttpRequest.BodyPublishers.ofString("ping"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()));
            assertTrue(passEntered.get().await(4, TimeUnit.SECONDS), "agentpass was not called");
            Thread.sleep(100);
            assertNoSidecarOnPlatformThreads();
            assertEquals(platformCap, platformWorkers().size());
            passRelease.get().countDown();
            HttpResponse<String> response = future.get(4, TimeUnit.SECONDS);
            assertEquals(401, response.statusCode());
        } finally {
            delayPass.set(false);
            passRelease.get().countDown();
        }
    }

    /**
     * 等待上游响应头时，Tomcat 平台工作线程已经回到池里，不待在转发调用上。
     */
    @Test
    void waitingForUpstreamHeadersDoesNotHoldPlatformWorkers() throws Exception {
        delayHeaders.set(true);
        headersEntered.set(new CountDownLatch(1));
        headersRelease.set(new CountDownLatch(1));
        try (HttpClient client = client(); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HttpResponse<String>> future = pool.submit(() -> client.send(
                    forward("http://127.0.0.1:" + upstream.getAddress().getPort() + "/hold")
                            .timeout(Duration.ofSeconds(6))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()));
            assertTrue(headersEntered.get().await(4, TimeUnit.SECONDS), "upstream was not called");
            Thread.sleep(100);
            assertNoSidecarOnPlatformThreads();
            assertEquals(platformCap, platformWorkers().size());
            headersRelease.get().countDown();
            HttpResponse<String> response = future.get(4, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertEquals("held", response.body());
        } finally {
            delayHeaders.set(false);
            headersRelease.get().countDown();
        }
    }

    /**
     * 先打一次健康检查和一次短转发，让选择器线程在基线里，避免把它算进流式期间新增的平台线程。
     *
     * @param client 测试用的 HTTP 客户端
     */
    private void warmup(HttpClient client) throws Exception {
        HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        HttpResponse<String> fast = client.send(forward(fastUrl()).timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, fast.statusCode());
        assertEquals("ok", fast.body());
    }

    /**
     * 读一路慢流的三段。每段单独计数，用来证明第一段在上游结束前已经到达，并且后面还在出块。
     *
     * @param client 测试用的 HTTP 客户端
     * @param call 这一路的进度
     */
    private void readSlow(HttpClient client, StreamCall call) {
        try {
            HttpResponse<InputStream> response = client.send(
                    forward(slowUrl()).timeout(Duration.ofSeconds(8)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            call.first = readExact(response.body(), 3);
            call.gotFirst.countDown();
            call.second = readExact(response.body(), 3);
            call.gotSecond.countDown();
            call.third = readExact(response.body(), 3);
            response.body().close();
        } catch (Throwable ex) {
            call.error = ex;
            call.gotFirst.countDown();
            call.gotSecond.countDown();
        }
    }

    /**
     * 组装打到 sidecar {@code /v1/forward} 的请求。目的地址由调用方给出。
     *
     * @param target 上游绝对地址
     * @return 还没设置超时的请求
     */
    private HttpRequest.Builder forward(String target) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + EgressPaths.FORWARD))
                .version(HttpClient.Version.HTTP_1_1)
                .header(EgressHeaders.URL, target)
                .POST(HttpRequest.BodyPublishers.ofString("ping"));
    }

    /**
     * 慢上游的绝对地址。
     *
     * @return 本机临时端口上的 {@code /slow}
     */
    private static String slowUrl() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort() + "/slow";
    }

    /**
     * 立刻返回的上游地址。
     *
     * @return 本机临时端口上的 {@code /fast}
     */
    private static String fastUrl() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort() + "/fast";
    }

    /**
     * 读满指定长度。流提前结束时返回已经读到的文本，方便断言失败信息。
     *
     * @param body 响应体
     * @param count 要读的字节数
     * @return 读到的文本
     */
    private static String readExact(InputStream body, int count) throws java.io.IOException {
        return new String(body.readNBytes(count), StandardCharsets.UTF_8);
    }

    /**
     * 当前进程的操作系统任务数。虚拟线程不会各占一条。
     *
     * @return {@code /proc/self/task} 下的条目数
     */
    private static int osTasks() {
        File[] tasks = new File("/proc/self/task").listFiles();
        return tasks == null ? 0 : tasks.length;
    }

    /**
     * 找出验收安装的 Tomcat 平台工作线程。这些线程不是虚拟线程。
     *
     * @return 名字以前缀开头的平台线程
     */
    private static List<Thread> platformWorkers() {
        List<Thread> found = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!thread.isVirtual() && thread.getName().startsWith(TomcatPlatformWorkerCustomizer.THREAD_NAME_PREFIX)) {
                found.add(thread);
            }
        }
        return found;
    }

    /**
     * 任何平台线程的栈里都不能出现 sidecar 的转发、通行证或抄写帧。
     * 虚拟线程上的这些帧是允许的；钉住载体时它们会出现在平台线程上。
     */
    private static void assertNoSidecarOnPlatformThreads() {
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            if (thread.isVirtual()) {
                continue;
            }
            for (StackTraceElement frame : entry.getValue()) {
                String type = frame.getClassName();
                if (type.startsWith("dev.jasagent.sidecar.") && !type.contains("Test")) {
                    throw new AssertionError(thread.getName() + " platform thread is in " + type + "." + frame.getMethodName());
                }
            }
        }
    }

    /**
     * 新建不跟随重定向的客户端。调用方负责关闭。
     *
     * @return HTTP/1.1 客户端
     */
    private static HttpClient client() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 在回环地址上开一个临时端口，处理线程用虚拟线程，避免把上游的等待算进平台线程。
     *
     * @return 尚未启动的服务器
     */
    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            return server;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 停掉临时服务器并关闭它的线程池。
     *
     * @param server 测试自己启动的服务器
     */
    private static void stop(HttpServer server) {
        server.stop(0);
        if (server.getExecutor() instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
    }

    /**
     * 先写出第一段并停住，等测试确认调用方已经看到，再写后续段。
     *
     * @param exchange 上游这次交换
     */
    private static void slow(HttpExchange exchange) throws java.io.IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write("a1\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        if (!await(releaseSecond.get())) {
            exchange.close();
            return;
        }
        out.write("a2\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        if (!await(releaseThird.get())) {
            exchange.close();
            return;
        }
        out.write("a3\n".getBytes(StandardCharsets.UTF_8));
        out.close();
    }

    /**
     * 立刻返回短正文，用来在长流进行时再开一路转发。
     *
     * @param exchange 上游这次交换
     */
    private static void fast(HttpExchange exchange) throws java.io.IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * 读完请求后先停住，再给响应头。用来制造等待上游的窗口。
     *
     * @param exchange 上游这次交换
     */
    private static void hold(HttpExchange exchange) throws java.io.IOException {
        exchange.getRequestBody().readAllBytes();
        if (delayHeaders.get()) {
            headersEntered.get().countDown();
            await(headersRelease.get());
        }
        byte[] body = "held".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * 校验接口。需要制造等待时，先读完请求再停住，然后返回无效报告。
     *
     * @param exchange 通行证这次交换
     */
    private static void verify(HttpExchange exchange) throws java.io.IOException {
        exchange.getRequestBody().readAllBytes();
        if (delayPass.get()) {
            passEntered.get().countDown();
            await(passRelease.get());
        }
        write(exchange, 200, "{\"proofValid\":false,\"used\":false,\"expired\":false,\"userMatch\":false,"
                + "\"targetMatch\":false,\"tenantMatch\":false,\"sessionMatch\":false,\"executionMatch\":false,"
                + "\"accepted\":false,\"reason\":\"proof-invalid\"}");
    }

    /**
     * 等到测试放行。超时返回假，调用方应关掉这次交换。
     *
     * @param latch 测试放行用的闩
     * @return 在超时前被放行时为真
     */
    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 写出一段不含证明文的 JSON。
     *
     * @param exchange 当前交换
     * @param status HTTP 状态码
     * @param json 响应正文
     */
    private static void write(HttpExchange exchange, int status, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * 一路长流式调用的三段进度。错误只留异常对象，不断言它的消息。
     */
    private static final class StreamCall {
        /**
         * 第一块已经读到。
         */
        private final CountDownLatch gotFirst = new CountDownLatch(1);
        /**
         * 第二块已经读到。
         */
        private final CountDownLatch gotSecond = new CountDownLatch(1);
        /**
         * 第一块文本。
         */
        private volatile String first;
        /**
         * 第二块文本。
         */
        private volatile String second;
        /**
         * 第三块文本。
         */
        private volatile String third;
        /**
         * 这一路的失败。只保留异常对象，不读取它的消息。
         */
        private volatile Throwable error;
    }
}
