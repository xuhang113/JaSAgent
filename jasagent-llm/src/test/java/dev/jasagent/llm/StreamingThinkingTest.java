package dev.jasagent.llm;

import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.llm.LlmChatRequest;
import dev.jasagent.api.llm.LlmChatResponse;
import dev.jasagent.api.llm.LlmMessage;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.llm.LlmToolCall;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对流式思考会在整段响应结束前送达，并且流里的工具调用仍能被收回。
 */
class StreamingThinkingTest {
    /**
     * 每个测试结束后卸掉思考出口，避免增量留到下一个测试。
     */
    @AfterEach
    void clearSink() {
        ThinkingLive.clear();
    }

    /**
     * 断言第一段思考在服务端写完剩余流之前就已经送达，最终正文、思考和请求里的流式与思考开关都符合约定。
     */
    @Test
    void thinkingDeltasArriveBeforeTheStreamEnds() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("");
        CopyOnWriteArrayList<String> deltas = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        AtomicBoolean responseFinished = new AtomicBoolean(false);
        AtomicBoolean sawDeltaBeforeEnd = new AtomicBoolean(false);
        ThinkingLive.bind((executionId, text) -> {
            deltas.add(text);
            if (!responseFinished.get()) {
                sawDeltaBeforeEnd.set(true);
            }
            first.countDown();
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                write(out, chunk("reasoning_content", "先"));
                out.flush();
                first.await(8, TimeUnit.SECONDS);
                write(out, chunk("reasoning_content", "想一步"));
                write(out, chunk("content", "答案"));
                write(out, """
                        data: {"id":"1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}

                        data: [DONE]

                        """);
                responseFinished.set(true);
                out.close();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.start();
        try {
            LlmChatResponse response = ask(server);
            assertEquals("答案", response.text());
            assertEquals("先想一步", response.thinking());
            assertEquals(List.of("先", "想一步"), deltas);
            assertTrue(sawDeltaBeforeEnd.get());
            String compact = seen.get().replace(" ", "").replace("\n", "");
            assertTrue(compact.contains("\"stream\":true"), seen.get());
            assertTrue(compact.contains("\"type\":\"enabled\""), seen.get());
            assertFalse(seen.get().contains("placeholder-key"), seen.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 流里先给思考、再分片给工具调用时，断言最终同时保留思考和完整的工具参数。
     */
    @Test
    void streamedToolCallIsStillReturned() throws Exception {
        ThinkingLive.bind((executionId, text) -> {
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    data: {"id":"1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"reasoning_content":"先看目录"}}]}

                    data: {"id":"1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"list_directory","arguments":""}}]}}]}

                    data: {"id":"1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"path\\":\\".\\"}"}}]}}]}

                    data: {"id":"1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LlmChatResponse response = ask(server);
            assertEquals("先看目录", response.thinking());
            assertEquals(1, response.toolCalls().size());
            LlmToolCall call = response.toolCalls().get(0);
            assertEquals("list_directory", call.name());
            assertTrue(call.arguments().contains("path"), call.arguments());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 用本地流式服务和打开思考的 DeepSeek 配置发起一次聊天。
     *
     * @param server 已经启动、按事件流返回补全的本地服务
     * @return 客户端整理后的聊天结果
     */
    private static LlmChatResponse ask(HttpServer server) {
        int port = server.getAddress().getPort();
        OpenAiCallConfig config = new OpenAiCallConfig(
                "http://127.0.0.1:" + port,
                "placeholder-key",
                "deepseek-flash",
                Duration.ofSeconds(10),
                0,
                false,
                false,
                Map.of("thinking", Map.of("type", "enabled")),
                true);
        LangChain4jLlmClient client = LangChain4jLlmClient.from(config);
        return client.chat(new LlmChatRequest(
                        "exec-live",
                        LlmTask.REACT,
                        "deepseek",
                        List.of(new LlmMessage("user", "工作区内有多少文件")),
                        1,
                        "deepseek-flash"))
                .toCompletableFuture()
                .join();
    }

    /**
     * 把一段事件流文本按 UTF-8 写进响应体。
     *
     * @param out 当前 HTTP 响应的输出流
     * @param text 要写出的事件流片段
     */
    private static void write(OutputStream out, String text) throws java.io.IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 拼一条只带一个增量字段的事件流数据帧。
     *
     * @param field 增量对象里的字段名
     * @param value 该字段的文本
     * @return 以 data 开头、并以空行结束的一帧
     */
    private static String chunk(String field, String value) {
        return """
                data: {"id":"1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"%s":"%s"}}]}

                """.formatted(field, value);
    }
}
