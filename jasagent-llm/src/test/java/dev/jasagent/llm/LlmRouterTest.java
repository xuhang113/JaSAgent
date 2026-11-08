package dev.jasagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.Embedding;
import dev.jasagent.api.llm.LlmCallRecord;
import dev.jasagent.api.llm.LlmCallSink;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.llm.ModelClient;
import dev.jasagent.api.llm.RouteTrace;
import dev.jasagent.api.text.SecretRedactor;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRouterTest {
    @Test
    void gatewayUnconfiguredReactUsesDeepseekFlash() {
        Harness harness = Harness.unconfigured();
        ChatResponse response = join(harness.router.complete(request(LlmTask.REACT, null)));
        assertEquals("deepseek", response.provider());
        assertEquals("deepseek-flash", response.model());
    }

    @Test
    void gatewayUnconfiguredCompressUsesDeepseekFlash() {
        Harness harness = Harness.unconfigured();
        ChatResponse response = join(harness.router.complete(request(LlmTask.COMPRESS, null)));
        assertEquals("deepseek", response.provider());
        assertEquals("deepseek-flash", response.model());
    }

    @Test
    void gatewayUnconfiguredRerankUsesDeepseekFlash() {
        Harness harness = Harness.unconfigured();
        ChatResponse response = join(harness.router.complete(request(LlmTask.RERANK, null)));
        assertEquals("deepseek-flash", response.model());
    }

    @Test
    void gatewayUnconfiguredJudgeUsesDeepseekFlash() {
        Harness harness = Harness.unconfigured();
        ChatResponse response = join(harness.router.complete(request(LlmTask.JUDGE, null)));
        assertEquals("deepseek-flash", response.model());
    }

    @Test
    void gatewayHealthyReactUsesGateway() {
        Harness harness = Harness.healthyGateway();
        ChatResponse response = join(harness.router.complete(request(LlmTask.REACT, null)));
        assertEquals("ai-gateway", response.provider());
        assertEquals(0, harness.deepseek.chats);
    }

    @Test
    void gatewayHealthyCompressStaysDeepseek() {
        Harness harness = Harness.healthyGateway();
        ChatResponse response = join(harness.router.complete(request(LlmTask.COMPRESS, null)));
        assertEquals("deepseek-flash", response.model());
        assertEquals(0, harness.gateway.chats);
    }

    @Test
    void gatewayHealthyRerankStaysDeepseek() {
        Harness harness = Harness.healthyGateway();
        ChatResponse response = join(harness.router.complete(request(LlmTask.RERANK, null)));
        assertEquals("deepseek-flash", response.model());
        assertEquals(0, harness.gateway.chats);
    }

    @Test
    void gatewayHealthyJudgeStaysDeepseek() {
        Harness harness = Harness.healthyGateway();
        ChatResponse response = join(harness.router.complete(request(LlmTask.JUDGE, null)));
        assertEquals("deepseek-flash", response.model());
        assertEquals(0, harness.gateway.chats);
    }

    @Test
    void thirdProviderFailsWithLlm() {
        Harness harness = Harness.healthyGateway();
        AgentException error = assertThrows(AgentException.class,
                () -> join(harness.router.complete(request(LlmTask.REACT, "vendor-three"))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(0, harness.deepseek.chats);
        assertEquals(0, harness.gateway.chats);
    }

    @Test
    void namedProviderUnavailableDoesNotSwitch() {
        Harness harness = Harness.healthyGateway();
        harness.deepseek.available = false;
        AgentException error = assertThrows(AgentException.class,
                () -> join(harness.router.complete(request(LlmTask.COMPRESS, "deepseek"))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(0, harness.gateway.chats);
    }

    @Test
    void reactGatewayFailureFallsBackToDeepseek() {
        Harness harness = Harness.healthyGateway();
        harness.gateway.failure = new AgentException(ErrorCode.LLM, "gateway failed midway");
        ChatResponse response = join(harness.router.complete(request(LlmTask.REACT, null)));
        assertEquals("deepseek-flash", response.model());
        assertTrue(response.fallback());
        assertTrue(harness.events.stream().anyMatch(event -> "deepseek-flash".equals(event.get("fallback"))));
    }

    @Test
    void compressFailureDoesNotFallBack() {
        Harness harness = Harness.healthyGateway();
        harness.deepseek.failure = new AgentException(ErrorCode.LLM, "deepseek failed");
        AgentException error = assertThrows(AgentException.class,
                () -> join(harness.router.complete(request(LlmTask.COMPRESS, null))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(0, harness.gateway.chats);
    }

    @Test
    void blankGatewayBaseIsUnconfigured() {
        Map<String, String> env = Map.of("JASAGENT_GATEWAY_BASE_URL", "  ");
        LlmSettings settings = LlmSettings.from(env, Map.of());
        assertFalse(settings.gatewayConfigured());
        Harness harness = Harness.unconfigured();
        ChatResponse response = join(harness.router.complete(request(LlmTask.REACT, null)));
        assertEquals("deepseek-flash", response.model());
    }

    @Test
    void embedDoesNotCallDeepseekAndUsesHashing() {
        Harness harness = Harness.healthyGateway();
        harness.gateway.embeddings = false;
        Embedding embedding = join(harness.router.embed("alpha beta", "exec-1"));
        assertEquals(0, harness.deepseek.embeds);
        assertEquals("hashing", embedding.embedder());
        assertTrue(harness.events.stream().anyMatch(event -> "hashing".equals(event.get("embedder"))));
        AgentException error = assertThrows(AgentException.class,
                () -> join(new DeepSeekClient(null, "placeholder-not-sent", java.net.URI.create("https://api.deepseek.com"), java.net.http.HttpClient.newHttpClient(), ignored -> {
                }).embed("alpha", "deepseek-flash", Duration.ofSeconds(1))));
        assertEquals(ErrorCode.LLM, error.code());
    }

    @Test
    void successfulCallIsRecorded() {
        Harness harness = Harness.unconfigured();
        ChatResponse response = join(harness.router.complete(request(LlmTask.REACT, null)));
        assertEquals(1, harness.records.size());
        LlmCallRecord record = harness.records.get(0);
        assertEquals(response.provider(), record.provider());
        assertEquals(response.model(), record.model());
        assertTrue(record.latencyMillis() >= 0);
        assertEquals(response.usage().promptTokens(), record.promptTokens());
        assertEquals(response.usage().completionTokens(), record.completionTokens());
    }

    private static ChatRequest request(LlmTask task, String namedProvider) {
        return new ChatRequest(task, List.of(ChatMessage.text("user", "hello")), List.of(), namedProvider, "exec-1");
    }

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

    private static final class Harness {
        private final FakeModel deepseek;
        private final FakeModel gateway;
        private final RoutingChatModel router;
        private final List<Map<String, String>> events = new ArrayList<>();
        private final List<LlmCallRecord> records = new ArrayList<>();

        private Harness(boolean configured, boolean healthy) {
            this.deepseek = new FakeModel("deepseek");
            this.gateway = new FakeModel("ai-gateway");
            RouteTrace trace = (type, fields) -> events.add(Map.copyOf(fields));
            LlmCallSink sink = record -> {
                records.add(record);
                return CompletableFuture.completedFuture(null);
            };
            this.router = new RoutingChatModel(deepseek, gateway, configured, healthy, "route-model", false, Duration.ofSeconds(60), trace, sink);
        }

        static Harness unconfigured() {
            return new Harness(false, false);
        }

        static Harness healthyGateway() {
            return new Harness(true, true);
        }
    }
}

class DeepSeekWireTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deepseekRequestUsesOnlyDeepseekFlash() throws Exception {
        String json = ChatPayloads.deepseek(List.of(ChatMessage.text("user", "hello")), List.of(), false);
        assertEquals("deepseek-flash", mapper.readTree(json).get("model").asText());
    }

    @Test
    void thinkingDisabledUnlessConfigured() throws Exception {
        JsonNode disabled = mapper.readTree(ChatPayloads.deepseek(List.of(ChatMessage.text("user", "hello")), List.of(), false));
        JsonNode enabled = mapper.readTree(ChatPayloads.deepseek(List.of(ChatMessage.text("user", "hello")), List.of(), true));
        assertEquals("disabled", disabled.get("thinking").get("type").asText());
        assertEquals("enabled", enabled.get("thinking").get("type").asText());
    }

    @Test
    void deepseekKeyComesOnlyFromEnv() {
        LlmSettings fromEnv = LlmSettings.from(Map.of("JASAGENT_DEEPSEEK_API_KEY", "env-deepseek-placeholder"), Map.of());
        LlmSettings fromProperties = LlmSettings.from(Map.of(), Map.of("JASAGENT_DEEPSEEK_API_KEY", "properties-deepseek-placeholder"));
        assertEquals("env-deepseek-placeholder", fromEnv.deepseekApiKey());
        assertTrue(fromProperties.deepseekApiKey() == null || fromProperties.deepseekApiKey().isBlank());
    }

    @Test
    void gatewayKeyComesOnlyFromEnv() {
        LlmSettings fromEnv = LlmSettings.from(Map.of("JASAGENT_GATEWAY_API_KEY", "env-gateway-placeholder"), Map.of());
        LlmSettings fromProperties = LlmSettings.from(Map.of(), Map.of("JASAGENT_GATEWAY_API_KEY", "properties-gateway-placeholder"));
        assertEquals("env-gateway-placeholder", fromEnv.gatewayApiKey());
        assertTrue(fromProperties.gatewayApiKey() == null || fromProperties.gatewayApiKey().isBlank());
    }

    @Test
    void authorizationIsRedacted() {
        String redacted = SecretRedactor.redact("Authorization: Bearer env-deepseek-placeholder");
        assertFalse(redacted.contains("env-deepseek-placeholder"));
    }

    @Test
    void gatewayBodyUsesCallerModel() throws Exception {
        String json = ChatPayloads.gateway("caller-selected-model", List.of(ChatMessage.text("user", "hello")), List.of());
        assertEquals("caller-selected-model", mapper.readTree(json).get("model").asText());
    }

    @Test
    void loopbackDeepseekOmitsKeyFromLog() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"choices":[{"message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
                    """.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            List<String> logs = new ArrayList<>();
            DeepSeekClient client = new DeepSeekClient(
                    null,
                    "wire-key-placeholder",
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    java.net.http.HttpClient.newHttpClient(),
                    logs::add);
            ChatResponse response = join(client.chat("deepseek-flash", new ChatRequest(LlmTask.REACT, List.of(ChatMessage.text("user", "hello")), List.of(), null, "exec-1"), Duration.ofSeconds(5), false));
            assertEquals("ok", response.message().content());
            assertEquals("Bearer wire-key-placeholder", authorization.get());
            assertFalse(String.join("\n", logs).contains("wire-key-placeholder"));
        } finally {
            server.stop(0);
        }
    }

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
}
