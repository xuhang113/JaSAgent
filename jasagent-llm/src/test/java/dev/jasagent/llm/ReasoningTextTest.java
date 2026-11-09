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

class ReasoningTextTest {
    @Test
    void readsDeepSeekReasoningContentFirst() {
        String json = """
                {"choices":[{"message":{"role":"assistant","content":"答案","reasoning_content":"先想一步","reasoning":"别的","thinking":"再别的"}}]}
                """;
        assertEquals("先想一步", ReasoningText.fromCompletionJson(json));
    }

    @Test
    void fallsThroughReasoningThenThinking() {
        assertEquals("中间层", ReasoningText.fromCompletionJson("""
                {"choices":[{"message":{"content":"答案","reasoning_content":"  ","reasoning":"中间层","thinking":"末层"}}]}
                """));
        assertEquals("末层", ReasoningText.fromCompletionJson("""
                {"choices":[{"message":{"content":"答案","thinking":"末层"}}]}
                """));
    }

    @Test
    void blankWhenReasoningIsMissing() {
        assertEquals("", ReasoningText.fromCompletionJson(null));
        assertEquals("", ReasoningText.fromCompletionJson(""));
        assertEquals("", ReasoningText.fromCompletionJson("{"));
        assertEquals("", ReasoningText.fromCompletionJson("""
                {"choices":[{"message":{"role":"assistant","content":"只有答案"}}]}
                """));
    }

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

    private static String completion(String content, String extra) {
        String fields = extra == null || extra.isBlank() ? "" : "," + extra;
        return """
                {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"deepseek-flash","choices":[{"index":0,"message":{"role":"assistant","content":"%s"%s},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}
                """.formatted(content, fields);
    }
}
