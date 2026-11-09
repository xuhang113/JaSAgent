package dev.jasagent.llm;

import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.llm.LlmChatRequest;
import dev.jasagent.api.llm.LlmChatResponse;
import dev.jasagent.api.llm.LlmMessage;
import dev.jasagent.api.llm.LlmTask;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对思考文本的字段优先级，以及 DeepSeek 兼容响应里思考和模型名的传递。
 */
class ReasoningTextTest {
    /**
     * 多个思考字段同时存在时，断言优先取 reasoning_content，而不是 reasoning 或 thinking。
     */
    @Test
    void readsDeepSeekReasoningContentFirst() {
        String json = """
                {"choices":[{"message":{"role":"assistant","content":"答案","reasoning_content":"先想一步","reasoning":"别的","thinking":"再别的"}}]}
                """;
        assertEquals("先想一步", ReasoningText.fromCompletionJson(json));
    }

    /**
     * reasoning_content 只有空白时断言退到 reasoning；只剩 thinking 时断言取 thinking。
     */
    @Test
    void fallsThroughReasoningThenThinking() {
        assertEquals("中间层", ReasoningText.fromCompletionJson("""
                {"choices":[{"message":{"content":"答案","reasoning_content":"  ","reasoning":"中间层","thinking":"末层"}}]}
                """));
        assertEquals("末层", ReasoningText.fromCompletionJson("""
                {"choices":[{"message":{"content":"答案","thinking":"末层"}}]}
                """));
    }

    /**
     * 对 null、空串、残缺 JSON 和没有思考字段的补全，断言思考结果都是空串。
     */
    @Test
    void blankWhenReasoningIsMissing() {
        assertEquals("", ReasoningText.fromCompletionJson(null));
        assertEquals("", ReasoningText.fromCompletionJson(""));
        assertEquals("", ReasoningText.fromCompletionJson("{"));
        assertEquals("", ReasoningText.fromCompletionJson("""
                {"choices":[{"message":{"role":"assistant","content":"只有答案"}}]}
                """));
    }

    /**
     * 用本地补全接口断言客户端能读出 reasoning_content，请求里带 deepseek-flash 和打开的思考，并且响应断言看不到密钥。
     */
    @Test
    void deepSeekFlashParsesReasoningContentFromTheResponse() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = completion("答案", "\"reasoning_content\":\"先想一步\"").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LlmChatResponse response = ask(server, "placeholder-key");
            assertEquals("答案", response.text());
            assertEquals("先想一步", response.thinking());
            assertTrue(seen.get().contains("deepseek-flash"), seen.get());
            assertTrue(seen.get().replace(" ", "").contains("\"type\":\"enabled\""), seen.get());
            assertFalse(seen.get().contains("placeholder-key"), seen.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 补全只有 thinking 字段时，断言聊天结果的思考取该字段，正文仍是答案。
     */
    @Test
    void thinkingFieldIsUsedWhenReasoningContentIsAbsent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = completion("答案", "\"thinking\":\"只在 thinking\"").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LlmChatResponse response = ask(server, "placeholder-key");
            assertEquals("答案", response.text());
            assertEquals("只在 thinking", response.thinking());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 请求另选模型时，断言发到补全路径的正文使用该模型名和思考开关，默认模型名不出现，密钥也不出现在正文里。
     */
    @Test
    void selectedModelIsPostedToTheDeepSeekEndpoint() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("");
        AtomicReference<String> path = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = completion("答案", "\"reasoning_content\":\"先想一步\"").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
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
            LlmChatResponse response = client.chat(new LlmChatRequest(
                            "exec-1",
                            LlmTask.REACT,
                            "deepseek",
                            List.of(new LlmMessage("user", "hi")),
                            1,
                            "deepseek-v4-pro"))
                    .toCompletableFuture()
                    .join();
            assertEquals("答案", response.text());
            assertEquals("/chat/completions", path.get());
            String compact = seen.get().replace(" ", "").replace("\n", "");
            assertTrue(compact.contains("\"model\":\"deepseek-v4-pro\""), seen.get());
            assertFalse(compact.contains("deepseek-flash"), seen.get());
            assertTrue(compact.contains("\"type\":\"enabled\""), seen.get());
            assertFalse(seen.get().contains("placeholder-key"), seen.get());
            assertEquals("https://api.deepseek.com", OpenAiCallConfig.DEEPSEEK_BASE_URL);
        } finally {
            server.stop(0);
        }
    }

    /**
     * 补全消息没有任何思考字段时，断言正文保留、思考为空。
     */
    @Test
    void responseWithoutReasoningLeavesThinkingEmpty() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = completion("只有答案", "").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LlmChatResponse response = ask(server, "placeholder-key");
            assertEquals("只有答案", response.text());
            assertEquals("", response.thinking());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 用本地服务的端口和打开思考的配置，发起一次不指定模型名的聊天。
     *
     * @param server 已经启动、提供补全接口的本地服务
     * @param apiKey 放进客户端配置的占位密钥
     * @return 客户端整理后的聊天结果
     */
    private static LlmChatResponse ask(HttpServer server, String apiKey) {
        int port = server.getAddress().getPort();
        OpenAiCallConfig config = new OpenAiCallConfig(
                "http://127.0.0.1:" + port,
                apiKey,
                "deepseek-flash",
                Duration.ofSeconds(10),
                0,
                false,
                false,
                Map.of("thinking", Map.of("type", "enabled")),
                true);
        LangChain4jLlmClient client = LangChain4jLlmClient.from(config);
        return client.chat(new LlmChatRequest(
                        "exec-1",
                        LlmTask.REACT,
                        null,
                        List.of(new LlmMessage("user", "hi")),
                        1))
                .toCompletableFuture()
                .join();
    }

    /**
     * 拼一条带用量的补全 JSON，把额外字段并进助手消息。
     *
     * @param content 助手正文
     * @param extra 要插入消息对象的额外 JSON 片段；空白表示不加
     * @return 一整段补全响应 JSON
     */
    private static String completion(String content, String extra) {
        String fields = extra == null || extra.isBlank() ? "" : "," + extra;
        return """
                {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"deepseek-flash","choices":[{"index":0,"message":{"role":"assistant","content":"%s"%s},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}
                """.formatted(content, fields);
    }
}
