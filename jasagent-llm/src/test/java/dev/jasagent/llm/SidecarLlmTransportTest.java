package dev.jasagent.llm;

import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.llm.LlmChatRequest;
import dev.jasagent.api.llm.LlmChatResponse;
import dev.jasagent.api.llm.LlmMessage;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.llm.egress.SidecarAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大模型离开集群时打到 sidecar 的转发入口，并且第一段响应在整段结束前就能读到。
 * 回环上的测试桩仍直连，不经过 sidecar。
 */
class SidecarLlmTransportTest {
    /**
     * 每个用例结束后卸掉思考出口和测试地址，避免留到下一个测试。
     */
    @AfterEach
    void clear() {
        ThinkingLive.clear();
        SidecarAddress.clearTest();
    }

    /**
     * 官方 DeepSeek 地址的流式请求改写到本机 sidecar。第一段思考在剩余响应写出之前已经送达。
     */
    @Test
    void publicModelStreamsThroughForward() throws Exception {
        AtomicReference<String> target = new AtomicReference<>("");
        AtomicReference<String> path = new AtomicReference<>("");
        AtomicBoolean sawAuthorization = new AtomicBoolean(false);
        CopyOnWriteArrayList<String> deltas = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        AtomicBoolean sawDeltaBeforeEnd = new AtomicBoolean(false);
        AtomicBoolean responseFinished = new AtomicBoolean(false);
        ThinkingLive.bind((executionId, text) -> {
            deltas.add(text);
            if (!responseFinished.get()) {
                sawDeltaBeforeEnd.set(true);
            }
            first.countDown();
        });
        HttpServer sidecar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sidecar.createContext(SidecarAddress.FORWARD_PATH, exchange -> {
            path.set(exchange.getRequestURI().getPath());
            target.set(exchange.getRequestHeaders().getFirst(SidecarAddress.URL_HEADER));
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            sawAuthorization.set(authorization != null && authorization.startsWith("Bearer "));
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            write(out, chunk("reasoning_content", "先"));
            out.flush();
            try {
                first.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            write(out, chunk("reasoning_content", "想一步"));
            write(out, chunk("content", "答案"));
            write(out, """
                    data: {"id":"1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """);
            responseFinished.set(true);
            out.close();
        });
        sidecar.start();
        try {
            SidecarAddress.useForTest("http://127.0.0.1:" + sidecar.getAddress().getPort());
            LlmChatResponse response = ask("https://api.deepseek.com");
            assertEquals(SidecarAddress.FORWARD_PATH, path.get());
            assertTrue(target.get().startsWith("https://api.deepseek.com/"), target.get());
            assertTrue(sawAuthorization.get());
            assertEquals("答案", response.text());
            assertEquals(List.of("先", "想一步"), deltas);
            assertTrue(sawDeltaBeforeEnd.get());
        } finally {
            sidecar.stop(0);
        }
    }

    /**
     * 回环上的模型桩由客户端直连。sidecar 若被打到，这次测试失败。
     */
    @Test
    void loopbackModelDoesNotUseSidecar() throws Exception {
        AtomicInteger sidecarHits = new AtomicInteger();
        AtomicInteger modelHits = new AtomicInteger();
        HttpServer sidecar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sidecar.createContext(SidecarAddress.FORWARD_PATH, exchange -> {
            sidecarHits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/chat/completions", exchange -> {
            modelHits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    {"id":"1","choices":[{"message":{"role":"assistant","content":"本地"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        sidecar.start();
        model.start();
        try {
            SidecarAddress.useForTest("http://127.0.0.1:" + sidecar.getAddress().getPort());
            LlmChatResponse response = ask("http://127.0.0.1:" + model.getAddress().getPort());
            assertEquals("本地", response.text());
            assertEquals(1, modelHits.get());
            assertEquals(0, sidecarHits.get());
        } finally {
            sidecar.stop(0);
            model.stop(0);
        }
    }

    /**
     * 用给定根地址发起一次不带工具的聊天。
     *
     * @param baseUrl 模型根地址。公网地址会改写，回环地址直连
     * @return 整理后的聊天结果
     */
    private static LlmChatResponse ask(String baseUrl) {
        OpenAiCallConfig config = new OpenAiCallConfig(
                baseUrl,
                "placeholder-key",
                "deepseek-flash",
                Duration.ofSeconds(8),
                0,
                false,
                false,
                Map.of("thinking", Map.of("type", "enabled")),
                true);
        LangChain4jLlmClient client = LangChain4jLlmClient.from(config);
        return client.chat(new LlmChatRequest(
                        "exec-sidecar",
                        LlmTask.REACT,
                        "deepseek",
                        List.of(new LlmMessage("user", "你好")),
                        1,
                        "deepseek-flash"))
                .toCompletableFuture()
                .join();
    }

    /**
     * 把一段事件流写进响应。
     *
     * @param out 响应输出
     * @param text 事件流片段
     */
    private static void write(OutputStream out, String text) throws java.io.IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 拼一条只带一个增量字段的事件流数据帧。
     *
     * @param field 增量字段名
     * @param text 这段增量
     * @return 一条 data 帧
     */
    private static String chunk(String field, String text) {
        return "data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\""
                + field + "\":\"" + text + "\"}}]}\n\n";
    }
}
