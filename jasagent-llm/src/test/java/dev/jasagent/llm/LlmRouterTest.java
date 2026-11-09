package dev.jasagent.llm;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.GatewayHealth;
import dev.jasagent.api.llm.LlmCall;
import dev.jasagent.api.llm.LlmCallStore;
import dev.jasagent.api.llm.LlmChatRequest;
import dev.jasagent.api.llm.LlmChatResponse;
import dev.jasagent.api.llm.LlmClient;
import dev.jasagent.api.llm.LlmEmbedRequest;
import dev.jasagent.api.llm.LlmEmbedResponse;
import dev.jasagent.api.llm.LlmMessage;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.llm.LlmTrace;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRouterTest {
    private static final String DEEPSEEK_KEY = "deepseek-placeholder-key";
    private static final String GATEWAY_KEY = "gateway-placeholder-key";
    private static final String GATEWAY_MODEL = "vendor-model";
    private static final String EMBED_MODEL = "embed-a";

    @Test
    void gatewayMissingReactUsesDeepseekFlash() {
        Fixture fixture = open(blankGateway(), () -> true);
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, null, 2)));
        assertDeepseek(response, fixture);
        assertEquals("answer", response.text());
    }

    @Test
    void gatewayMissingCompressUsesDeepseekFlash() {
        Fixture fixture = open(settings("", false, false, ""), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.COMPRESS, null, 2))), fixture);
    }

    @Test
    void gatewayMissingRerankUsesDeepseekFlash() {
        Fixture fixture = open(settings("", false, false, ""), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.RERANK, null, 2))), fixture);
    }

    @Test
    void gatewayMissingJudgeUsesDeepseekFlash() {
        Fixture fixture = open(settings("", false, false, ""), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.JUDGE, null, 2))), fixture);
    }

    @Test
    void gatewayHealthyReactUsesGatewayModel() {
        Fixture fixture = open(configured(false), () -> true);
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, null, 2)));
        assertEquals("ai-gateway", response.provider());
        assertEquals(GATEWAY_MODEL, response.model());
        assertFalse(response.fallback());
        assertEquals(1, fixture.gateway.chats.size());
        assertEquals(0, fixture.deepseek.chats.size());
    }

    @Test
    void gatewayHealthyCompressStaysOnDeepseek() {
        Fixture fixture = open(configured(false), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.COMPRESS, null, 2))), fixture);
    }

    @Test
    void gatewayHealthyRerankStaysOnDeepseek() {
        Fixture fixture = open(configured(false), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.RERANK, null, 2))), fixture);
    }

    @Test
    void gatewayHealthyJudgeStaysOnDeepseek() {
        Fixture fixture = open(configured(false), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.JUDGE, null, 2))), fixture);
    }

    @Test
    void pinnedDeepseekIgnoresHealthyGateway() {
        Fixture fixture = open(configured(false), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.REACT, "deepseek", 2))), fixture);
    }

    @Test
    void pinnedGatewayUnavailableDoesNotSwitch() {
        Fixture fixture = open(configured(false), () -> false);
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(LlmTask.REACT, "ai-gateway", 2))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(0, fixture.deepseek.chats.size());
        assertEquals(0, fixture.gateway.chats.size());
    }

    @Test
    void pinnedDeepseekUnavailableDoesNotSwitch() {
        LlmSettings settings = new LlmSettings("", false, "https://gateway.example", GATEWAY_KEY, GATEWAY_MODEL, false, "", Duration.ofSeconds(60));
        Fixture fixture = open(settings, () -> true);
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(LlmTask.REACT, "deepseek", 2))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(0, fixture.gateway.chats.size());
        assertEquals(0, fixture.deepseek.chats.size());
    }

    @Test
    void thirdProviderFailsWithLlm() {
        Fixture fixture = open(configured(false), () -> true);
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(LlmTask.REACT, "other-vendor", 2))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(0, fixture.deepseek.chats.size());
        assertEquals(0, fixture.gateway.chats.size());
    }

    @Test
    void reactFallsBackToDeepseekAndTracesFallback() {
        Fixture fixture = open(configured(false), () -> true);
        fixture.gateway.failure = new AgentException(ErrorCode.LLM, "gateway down");
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, null, 2)));
        assertEquals("deepseek", response.provider());
        assertEquals("deepseek-flash", response.model());
        assertTrue(response.fallback());
        assertTrue(fixture.trace.text().contains("fallback"));
        assertEquals(1, fixture.store.calls.size());
        assertTrue(fixture.store.calls.get(0).fallback());
    }

    @Test
    void compressFailureDoesNotReroute() {
        assertTaskFailureDoesNotCallGateway(LlmTask.COMPRESS);
    }

    @Test
    void rerankFailureDoesNotReroute() {
        assertTaskFailureDoesNotCallGateway(LlmTask.RERANK);
    }

    @Test
    void judgeFailureDoesNotReroute() {
        assertTaskFailureDoesNotCallGateway(LlmTask.JUDGE);
    }

    @Test
    void reactGatewayTimeoutFallsBackWithoutRetryingGateway() {
        Fixture fixture = open(configured(false), () -> true);
        fixture.gateway.timeouts = 1;
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, null, 2)));
        assertEquals(1, fixture.gateway.chats.size());
        assertEquals(1, fixture.deepseek.chats.size());
        assertEquals("deepseek-flash", response.model());
        assertTrue(response.fallback());
    }

    @Test
    void reactDeepseekTimeoutRetriesOnceWhenStepsRemain() {
        Fixture fixture = open(settings("", false, false, ""), () -> false);
        fixture.deepseek.timeouts = 1;
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, null, 1)));
        assertEquals(2, fixture.deepseek.chats.size());
        assertEquals("deepseek-flash", response.model());
        assertFalse(response.fallback());
    }

    @Test
    void reactDeepseekTimeoutDoesNotRetryWithoutSteps() {
        Fixture fixture = open(settings("", false, false, ""), () -> false);
        fixture.deepseek.timeouts = 1;
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(LlmTask.REACT, null, 0))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(1, fixture.deepseek.chats.size());
    }

    @Test
    void reactTimeoutRetriesAtMostOnce() {
        Fixture fixture = open(settings("", false, false, ""), () -> false);
        fixture.deepseek.timeouts = 2;
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(LlmTask.REACT, null, 4))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(2, fixture.deepseek.chats.size());
    }

    @Test
    void pinnedGatewayTimeoutRetriesGatewayOnly() {
        Fixture fixture = open(configured(false), () -> true);
        fixture.gateway.timeouts = 1;
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, "ai-gateway", 2)));
        assertEquals(2, fixture.gateway.chats.size());
        assertEquals(0, fixture.deepseek.chats.size());
        assertEquals("ai-gateway", response.provider());
    }

    @Test
    void embedUsesGatewayWhenConfigured() {
        Fixture fixture = open(embeddingSettings(true, EMBED_MODEL), () -> true);
        LlmEmbedResponse response = join(fixture.router.embed(new LlmEmbedRequest("exec-1", "hello")));
        assertEquals("ai-gateway", response.embedder());
        assertEquals(EMBED_MODEL, response.model());
        assertEquals(0, fixture.deepseek.embeds.size());
        assertEquals(1, fixture.gateway.embeds.size());
        assertTrue(fixture.trace.text().contains("embedder=ai-gateway"));
    }

    @Test
    void embedUsesHashingWhenGatewayHasNoEmbeddings() {
        Fixture fixture = open(settings("https://gateway.example", false, false, ""), () -> true);
        LlmEmbedResponse first = join(fixture.router.embed(new LlmEmbedRequest("exec-1", "hello")));
        LlmEmbedResponse second = join(fixture.router.embed(new LlmEmbedRequest("exec-1", "hello")));
        LlmEmbedResponse other = join(fixture.router.embed(new LlmEmbedRequest("exec-1", "world")));
        assertEquals(256, first.vector().length);
        assertEquals(256, second.vector().length);
        assertArrayEquals(first.vector(), second.vector());
        assertFalse(java.util.Arrays.equals(first.vector(), other.vector()));
        assertEquals("hashing", first.embedder());
        assertTrue(fixture.trace.text().contains("embedder=hashing"));
        assertEquals(0, fixture.deepseek.embeds.size());
        assertEquals(0, fixture.gateway.embeds.size());
    }

    @Test
    void blankEmbeddingModelUsesHashing() {
        Fixture fixture = open(embeddingSettings(true, "  "), () -> true);
        LlmEmbedResponse response = join(fixture.router.embed(new LlmEmbedRequest("exec-1", "hello")));
        assertEquals("hashing", response.embedder());
        assertTrue(fixture.trace.text().contains("embedder=hashing"));
        assertEquals(0, fixture.gateway.embeds.size());
    }

    @Test
    void unhealthyGatewayEmbedUsesHashing() {
        Fixture fixture = open(embeddingSettings(true, EMBED_MODEL), () -> false);
        LlmEmbedResponse response = join(fixture.router.embed(new LlmEmbedRequest("exec-1", "hello")));
        assertEquals("hashing", response.embedder());
        assertEquals(0, fixture.gateway.embeds.size());
    }

    @Test
    void chatRecordStoresProviderModelLatencyAndTokens() {
        Fixture fixture = open(settings("", false, false, ""), () -> false);
        join(fixture.router.chat(chat(LlmTask.COMPRESS, null, 1)));
        assertEquals(1, fixture.store.calls.size());
        LlmCall call = fixture.store.calls.get(0);
        assertEquals("deepseek", call.provider());
        assertEquals("deepseek-flash", call.model());
        assertTrue(call.latencyMillis() >= 0);
        assertEquals(11, call.promptTokens());
        assertEquals(7, call.completionTokens());
        assertEquals(18, call.totalTokens());
    }

    @Test
    void authorizationRedactorStripsHeader() {
        String raw = "headers Authorization: Bearer unit-test-secret-key\nnext";
        String cleaned = AuthorizationRedactor.redact(raw);
        assertFalse(cleaned.contains("Authorization"));
        assertFalse(cleaned.contains("Bearer"));
        assertFalse(cleaned.contains("unit-test-secret-key"));
        assertTrue(cleaned.contains("next"));
    }

    @Test
    void traceDoesNotContainApiKey() {
        Fixture fixture = open(configured(false), () -> true);
        join(fixture.router.chat(chat(LlmTask.COMPRESS, null, 1)));
        fixture.gateway.failure = new AgentException(ErrorCode.LLM, "Authorization: Bearer " + GATEWAY_KEY);
        join(fixture.router.chat(chat(LlmTask.REACT, null, 1)));
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(LlmTask.REACT, "other-vendor", 1))));
        String written = fixture.trace.text() + error.getMessage();
        assertFalse(written.contains(DEEPSEEK_KEY));
        assertFalse(written.contains(GATEWAY_KEY));
    }

    @Test
    void defaultTimeoutIsSixtySeconds() {
        LlmSettings settings = LlmSettings.fromEnvironment(Map.of("JASAGENT_DEEPSEEK_API_KEY", DEEPSEEK_KEY));
        assertEquals(Duration.ofSeconds(60), settings.timeout());
    }

    @Test
    void timeoutSecondsCanBeOverridden() {
        LlmSettings settings = LlmSettings.fromEnvironment(Map.of("JASAGENT_LLM_TIMEOUT_SECONDS", "15"));
        assertEquals(Duration.ofSeconds(15), settings.timeout());
    }

    @Test
    void deepSeekModelNameAndBaseUrl() throws Exception {
        OpenAiChatModel model = LangChain4jModels.chat(OpenAiCallConfig.deepSeek(configured(false)));
        assertEquals("deepseek-flash", model.defaultRequestParameters().modelName());
        assertEquals("https://api.deepseek.com", clientBaseUrl(model));
    }

    @Test
    void deepSeekThinkingDisabledByDefault() {
        OpenAiChatModel model = LangChain4jModels.chat(OpenAiCallConfig.deepSeek(configured(false)));
        assertEquals("disabled", thinkingType(model));
    }

    @Test
    void routerKeepsReasoningText() {
        Fixture fixture = open(settings("", false, false, ""), () -> false);
        fixture.deepseek.response = new LlmChatResponse("ignored", "ignored", "答案", 1, 2, 3, false, "先想一步");
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, null, 1)));
        assertEquals("答案", response.text());
        assertEquals("先想一步", response.thinking());
        assertEquals("deepseek-flash", response.model());
    }

    @Test
    void deepSeekThinkingEnabledFromEnvironment() {
        LlmSettings settings = LlmSettings.fromEnvironment(Map.of(
                "JASAGENT_DEEPSEEK_API_KEY", DEEPSEEK_KEY,
                "JASAGENT_DEEPSEEK_THINKING", "true"));
        OpenAiChatModel model = LangChain4jModels.chat(OpenAiCallConfig.deepSeek(settings));
        assertEquals("enabled", thinkingType(model));
    }

    @Test
    void langchain4jDisablesLibraryRetryAndRequestLogs() throws Exception {
        OpenAiChatModel model = LangChain4jModels.chat(OpenAiCallConfig.deepSeek(configured(false)));
        assertEquals(0, reflectiveInt(model, "maxRetries"));
        Object client = reflective(model, "client");
        Object httpClient = reflective(client, "httpClient");
        assertFalse(httpClient.getClass().getName().contains("Logging"));
    }

    @Test
    void gatewayChatModelUsesConfiguredEndpoint() throws Exception {
        OpenAiChatModel model = LangChain4jModels.chat(OpenAiCallConfig.gatewayChat(configured(false)));
        assertEquals(GATEWAY_MODEL, model.defaultRequestParameters().modelName());
        assertEquals("https://gateway.example", clientBaseUrl(model));
        OpenAiChatRequestParameters parameters = (OpenAiChatRequestParameters) model.defaultRequestParameters();
        Map<String, Object> custom = parameters.customParameters();
        assertTrue(custom == null || !custom.containsKey("thinking"));
    }

    @Test
    void moduleDoesNotUseSpringOrHandWrittenHttpClient() throws Exception {
        String source = readMainSources();
        assertFalse(source.contains("java.net.http"));
        assertFalse(source.contains("httpClientBuilder"));
        String pom = Files.readString(Path.of("pom.xml"));
        assertFalse(pom.contains("springframework"));
        assertFalse(pom.contains("spring-ai"));
        assertFalse(pom.contains("spring-boot"));
        assertTrue(pom.contains("langchain4j-open-ai"));
        Class.forName("dev.langchain4j.model.openai.OpenAiChatModel");
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.context.ApplicationContext"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.ai.openai.OpenAiChatModel"));
    }

    @Test
    void chatRunsOnVirtualThread() {
        Fixture fixture = open(settings("", false, false, ""), () -> false);
        CompletionStage<LlmChatResponse> stage = fixture.router.chat(chat(LlmTask.REACT, null, 1));
        assertInstanceOf(CompletionStage.class, stage);
        join(stage);
        assertEquals(1, fixture.deepseek.virtual.size());
        assertTrue(fixture.deepseek.virtual.get(0));
    }

    @Test
    void unhealthyGatewayUsesDeepseekForEveryTask() {
        Fixture fixture = open(configured(false), () -> false);
        for (LlmTask task : List.of(LlmTask.REACT, LlmTask.COMPRESS, LlmTask.RERANK, LlmTask.JUDGE)) {
            LlmChatResponse response = join(fixture.router.chat(chat(task, null, 1)));
            assertEquals("deepseek", response.provider());
            assertEquals("deepseek-flash", response.model());
        }
        assertEquals(0, fixture.gateway.chats.size());
        assertEquals(4, fixture.deepseek.chats.size());
    }

    @Test
    void chosenDeepSeekModelSkipsHealthyGateway() {
        Fixture fixture = open(configured(false), () -> true);
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, "deepseek", "deepseek-v4-pro", 1)));
        assertEquals("deepseek", response.provider());
        assertEquals("deepseek-v4-pro", response.model());
        assertFalse(response.fallback());
        assertEquals(1, fixture.deepseek.chats.size());
        assertEquals("deepseek-v4-pro", fixture.deepseek.chats.get(0).model());
        assertEquals(0, fixture.gateway.chats.size());
    }

    @Test
    void chosenGatewayModelDoesNotCallDeepSeek() {
        Fixture fixture = open(configured(false), () -> true);
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, "ai-gateway", GATEWAY_MODEL, 1)));
        assertEquals("ai-gateway", response.provider());
        assertEquals(GATEWAY_MODEL, response.model());
        assertEquals(0, fixture.deepseek.chats.size());
        assertEquals(1, fixture.gateway.chats.size());
        assertEquals(GATEWAY_MODEL, fixture.gateway.chats.get(0).model());
    }

    @Test
    void unavailableGatewayChoiceFailsWithoutFallingBack() {
        Fixture fixture = open(blankGateway(), () -> false);
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(LlmTask.REACT, "ai-gateway", GATEWAY_MODEL, 1))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(0, fixture.deepseek.chats.size());
        assertEquals(0, fixture.gateway.chats.size());
    }

    @Test
    void deepSeekClientRejectsEmbed() {
        LangChain4jLlmClient client = LangChain4jLlmClient.deepSeek(configured(false));
        AgentException error = assertThrows(AgentException.class,
                () -> join(client.embed(new LlmEmbedRequest("exec-1", "hello"))));
        assertEquals(ErrorCode.LLM, error.code());
    }

    private static void assertTaskFailureDoesNotCallGateway(LlmTask task) {
        Fixture fixture = open(configured(false), () -> true);
        fixture.deepseek.failure = new AgentException(ErrorCode.LLM, "deepseek down");
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(task, null, 2))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(0, fixture.gateway.chats.size());
    }

    private static void assertDeepseek(LlmChatResponse response, Fixture fixture) {
        assertEquals("deepseek", response.provider());
        assertEquals("deepseek-flash", response.model());
        assertFalse(response.fallback());
        assertEquals(1, fixture.deepseek.chats.size());
        assertEquals(0, fixture.gateway.chats.size());
    }

    private static LlmChatRequest chat(LlmTask task, String provider, int remainingSteps) {
        return new LlmChatRequest("exec-1", task, provider, List.of(new LlmMessage("user", "hello")), remainingSteps);
    }

    private static LlmChatRequest chat(LlmTask task, String provider, String model, int remainingSteps) {
        return new LlmChatRequest("exec-1", task, provider, List.of(new LlmMessage("user", "hello")), remainingSteps, model);
    }

    private static LlmSettings blankGateway() {
        return settings("   ", false, false, "");
    }

    private static LlmSettings configured(boolean thinking) {
        return new LlmSettings(DEEPSEEK_KEY, thinking, "https://gateway.example", GATEWAY_KEY, GATEWAY_MODEL, false, "", Duration.ofSeconds(60));
    }

    private static LlmSettings settings(String gatewayBase, boolean thinking, boolean embeddings, String embeddingModel) {
        return new LlmSettings(DEEPSEEK_KEY, thinking, gatewayBase, GATEWAY_KEY, GATEWAY_MODEL, embeddings, embeddingModel, Duration.ofSeconds(60));
    }

    private static LlmSettings embeddingSettings(boolean embeddings, String embeddingModel) {
        return new LlmSettings(DEEPSEEK_KEY, false, "https://gateway.example", GATEWAY_KEY, GATEWAY_MODEL, embeddings, embeddingModel, Duration.ofSeconds(60));
    }

    private static Fixture open(LlmSettings settings, GatewayHealth health) {
        FakeLlmClient deepseek = new FakeLlmClient();
        FakeLlmClient gateway = new FakeLlmClient();
        MemoryStore store = new MemoryStore();
        MemoryTrace trace = new MemoryTrace();
        LlmRouter router = new LlmRouter(settings, health, deepseek, gateway, store, trace);
        return new Fixture(router, deepseek, gateway, store, trace);
    }

    private static String thinkingType(OpenAiChatModel model) {
        OpenAiChatRequestParameters parameters = (OpenAiChatRequestParameters) model.defaultRequestParameters();
        Object value = parameters.customParameters().get("thinking");
        @SuppressWarnings("unchecked")
        Map<String, Object> thinking = (Map<String, Object>) value;
        return String.valueOf(thinking.get("type"));
    }

    private static String clientBaseUrl(OpenAiChatModel model) throws Exception {
        Object client = reflective(model, "client");
        return String.valueOf(reflective(client, "baseUrl"));
    }

    private static int reflectiveInt(Object target, String name) throws Exception {
        return ((Number) reflective(target, name)).intValue();
    }

    private static Object reflective(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static String readMainSources() throws Exception {
        Path root = Path.of("src/main/java");
        StringBuilder builder = new StringBuilder();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                builder.append(Files.readString(path)).append('\n');
            }
        }
        return builder.toString();
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Fixture(LlmRouter router, FakeLlmClient deepseek, FakeLlmClient gateway, MemoryStore store, MemoryTrace trace) {
    }

    static final class FakeLlmClient implements LlmClient {
        final List<LlmChatRequest> chats = new ArrayList<>();
        final List<LlmEmbedRequest> embeds = new ArrayList<>();
        final List<Boolean> virtual = new ArrayList<>();
        int timeouts;
        RuntimeException failure;
        LlmChatResponse response = new LlmChatResponse("ignored", "ignored", "answer", 11, 7, 18, false);

        @Override
        public CompletionStage<LlmChatResponse> chat(LlmChatRequest request) {
            virtual.add(Thread.currentThread().isVirtual());
            chats.add(request);
            if (timeouts > 0) {
                timeouts--;
                throw new LlmTimeoutException(request.executionId(), "超时");
            }
            if (failure != null) {
                RuntimeException error = failure;
                failure = null;
                throw error;
            }
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request) {
            virtual.add(Thread.currentThread().isVirtual());
            embeds.add(request);
            return CompletableFuture.completedFuture(new LlmEmbedResponse("from-client", "from-client", new float[] {0.25f}));
        }
    }

    static final class MemoryStore implements LlmCallStore {
        final List<LlmCall> calls = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<Void> save(LlmCall call) {
            calls.add(call);
            return CompletableFuture.completedFuture(null);
        }
    }

    static final class MemoryTrace implements LlmTrace {
        final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<Void> write(String executionId, String line) {
            lines.add(line);
            return CompletableFuture.completedFuture(null);
        }

        String text() {
            return String.join("\n", lines);
        }
    }
}
