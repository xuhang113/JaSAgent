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

/**
 * 覆盖模型路由：供应方选择、超时重试、嵌入回退、调用记录和密钥不落追踪。
 */
class LlmRouterTest {
    /**
     * 测试里代替真实 DeepSeek 密钥的占位文本。
     */
    private static final String DEEPSEEK_KEY = "deepseek-placeholder-key";
    /**
     * 测试里代替真实网关密钥的占位文本。
     */
    private static final String GATEWAY_KEY = "gateway-placeholder-key";
    /**
     * 测试配置的网关聊天模型名。
     */
    private static final String GATEWAY_MODEL = "vendor-model";
    /**
     * 测试配置的网关嵌入模型名。
     */
    private static final String EMBED_MODEL = "embed-a";

    /**
     * 网关地址空白时，断言 REACT 聊天落到 DeepSeek flash，并带回预设回答。
     */
    @Test
    void gatewayMissingReactUsesDeepseekFlash() {
        Fixture fixture = open(blankGateway(), () -> true);
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, null, 2)));
        assertDeepseek(response, fixture);
        assertEquals("answer", response.text());
    }

    /**
     * 网关地址是空串时，断言压缩任务仍使用 DeepSeek flash。
     */
    @Test
    void gatewayMissingCompressUsesDeepseekFlash() {
        Fixture fixture = open(settings("", false, false, ""), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.COMPRESS, null, 2))), fixture);
    }

    /**
     * 网关地址是空串时，断言重排任务仍使用 DeepSeek flash。
     */
    @Test
    void gatewayMissingRerankUsesDeepseekFlash() {
        Fixture fixture = open(settings("", false, false, ""), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.RERANK, null, 2))), fixture);
    }

    /**
     * 网关地址是空串时，断言评判任务仍使用 DeepSeek flash。
     */
    @Test
    void gatewayMissingJudgeUsesDeepseekFlash() {
        Fixture fixture = open(settings("", false, false, ""), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.JUDGE, null, 2))), fixture);
    }

    /**
     * 网关健康时，断言 REACT 只打网关模型，不落到 DeepSeek，也不标成回退。
     */
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

    /**
     * 网关健康时，断言压缩任务仍然只走 DeepSeek。
     */
    @Test
    void gatewayHealthyCompressStaysOnDeepseek() {
        Fixture fixture = open(configured(false), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.COMPRESS, null, 2))), fixture);
    }

    /**
     * 网关健康时，断言重排任务仍然只走 DeepSeek。
     */
    @Test
    void gatewayHealthyRerankStaysOnDeepseek() {
        Fixture fixture = open(configured(false), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.RERANK, null, 2))), fixture);
    }

    /**
     * 网关健康时，断言评判任务仍然只走 DeepSeek。
     */
    @Test
    void gatewayHealthyJudgeStaysOnDeepseek() {
        Fixture fixture = open(configured(false), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.JUDGE, null, 2))), fixture);
    }

    /**
     * 请求钉住 DeepSeek 时，断言健康的网关不会被选中。
     */
    @Test
    void pinnedDeepseekIgnoresHealthyGateway() {
        Fixture fixture = open(configured(false), () -> true);
        assertDeepseek(join(fixture.router.chat(chat(LlmTask.REACT, "deepseek", 2))), fixture);
    }

    /**
     * 钉住网关但网关不健康时，断言直接失败，并且两边客户端都没有收到请求。
     */
    @Test
    void pinnedGatewayUnavailableDoesNotSwitch() {
        Fixture fixture = open(configured(false), () -> false);
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(LlmTask.REACT, "ai-gateway", 2))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(0, fixture.deepseek.chats.size());
        assertEquals(0, fixture.gateway.chats.size());
    }

    /**
     * 钉住 DeepSeek 但没有密钥时，断言失败，并且不改去调用已配置的网关。
     */
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

    /**
     * 供应方既不是 DeepSeek 也不是网关时，断言得到模型错误，且两个客户端都未被调用。
     */
    @Test
    void thirdProviderFailsWithLlm() {
        Fixture fixture = open(configured(false), () -> true);
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(LlmTask.REACT, "other-vendor", 2))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(0, fixture.deepseek.chats.size());
        assertEquals(0, fixture.gateway.chats.size());
    }

    /**
     * REACT 的网关调用失败时，断言改走 DeepSeek、标成回退，并在追踪和调用记录里留下回退。
     */
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

    /**
     * 压缩任务在 DeepSeek 失败时，断言不会改道到网关。
     */
    @Test
    void compressFailureDoesNotReroute() {
        assertTaskFailureDoesNotCallGateway(LlmTask.COMPRESS);
    }

    /**
     * 重排任务在 DeepSeek 失败时，断言不会改道到网关。
     */
    @Test
    void rerankFailureDoesNotReroute() {
        assertTaskFailureDoesNotCallGateway(LlmTask.RERANK);
    }

    /**
     * 评判任务在 DeepSeek 失败时，断言不会改道到网关。
     */
    @Test
    void judgeFailureDoesNotReroute() {
        assertTaskFailureDoesNotCallGateway(LlmTask.JUDGE);
    }

    /**
     * REACT 的网关超时时，断言网关只被调用一次，然后回退到 DeepSeek flash。
     */
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

    /**
     * REACT 还剩步数时，断言 DeepSeek 超时后对同一边再试一次并成功。
     */
    @Test
    void reactDeepseekTimeoutRetriesOnceWhenStepsRemain() {
        Fixture fixture = open(settings("", false, false, ""), () -> false);
        fixture.deepseek.timeouts = 1;
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, null, 1)));
        assertEquals(2, fixture.deepseek.chats.size());
        assertEquals("deepseek-flash", response.model());
        assertFalse(response.fallback());
    }

    /**
     * REACT 没有剩余步数时，断言 DeepSeek 超时不再重试，只调用一次。
     */
    @Test
    void reactDeepseekTimeoutDoesNotRetryWithoutSteps() {
        Fixture fixture = open(settings("", false, false, ""), () -> false);
        fixture.deepseek.timeouts = 1;
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(LlmTask.REACT, null, 0))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(1, fixture.deepseek.chats.size());
    }

    /**
     * 连续超时且剩余步数很多时，断言同一边最多再试一次，第二次超时后失败。
     */
    @Test
    void reactTimeoutRetriesAtMostOnce() {
        Fixture fixture = open(settings("", false, false, ""), () -> false);
        fixture.deepseek.timeouts = 2;
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(LlmTask.REACT, null, 4))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(2, fixture.deepseek.chats.size());
    }

    /**
     * 钉住网关时，断言超时只在网关上重试，不会改去 DeepSeek。
     */
    @Test
    void pinnedGatewayTimeoutRetriesGatewayOnly() {
        Fixture fixture = open(configured(false), () -> true);
        fixture.gateway.timeouts = 1;
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, "ai-gateway", 2)));
        assertEquals(2, fixture.gateway.chats.size());
        assertEquals(0, fixture.deepseek.chats.size());
        assertEquals("ai-gateway", response.provider());
    }

    /**
     * 嵌入开关、模型和健康检查都具备时，断言向量来自网关，并写下网关嵌入追踪。
     */
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

    /**
     * 网关没开嵌入时，断言相同文本得到相同的 256 维哈希向量，不同文本则不同，且不调用远程嵌入。
     */
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

    /**
     * 嵌入模型名只有空白时，断言改用哈希嵌入，并且不访问网关。
     */
    @Test
    void blankEmbeddingModelUsesHashing() {
        Fixture fixture = open(embeddingSettings(true, "  "), () -> true);
        LlmEmbedResponse response = join(fixture.router.embed(new LlmEmbedRequest("exec-1", "hello")));
        assertEquals("hashing", response.embedder());
        assertTrue(fixture.trace.text().contains("embedder=hashing"));
        assertEquals(0, fixture.gateway.embeds.size());
    }

    /**
     * 网关不健康时，断言嵌入改走哈希，并且不把请求发给网关。
     */
    @Test
    void unhealthyGatewayEmbedUsesHashing() {
        Fixture fixture = open(embeddingSettings(true, EMBED_MODEL), () -> false);
        LlmEmbedResponse response = join(fixture.router.embed(new LlmEmbedRequest("exec-1", "hello")));
        assertEquals("hashing", response.embedder());
        assertEquals(0, fixture.gateway.embeds.size());
    }

    /**
     * 一次压缩聊天后，断言调用记录里有 DeepSeek flash、非负耗时和客户端给出的 token 数。
     */
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

    /**
     * 断言脱敏会去掉 Authorization 头、Bearer 和密钥正文，并留下后面的普通文本。
     */
    @Test
    void authorizationRedactorStripsHeader() {
        String raw = "headers Authorization: Bearer unit-test-secret-key\nnext";
        String cleaned = AuthorizationRedactor.redact(raw);
        assertFalse(cleaned.contains("Authorization"));
        assertFalse(cleaned.contains("Bearer"));
        assertFalse(cleaned.contains("unit-test-secret-key"));
        assertTrue(cleaned.contains("next"));
    }

    /**
     * 网关失败信息和未知供应方错误经过路由后，断言追踪和异常说明里都没有两把测试密钥。
     */
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

    /**
     * 环境只给了 DeepSeek 密钥时，断言调用超时是 60 秒。
     */
    @Test
    void defaultTimeoutIsSixtySeconds() {
        LlmSettings settings = LlmSettings.fromEnvironment(Map.of("JASAGENT_DEEPSEEK_API_KEY", DEEPSEEK_KEY));
        assertEquals(Duration.ofSeconds(60), settings.timeout());
    }

    /**
     * 环境写了超时秒数时，断言配置使用该秒数而不是默认的 60 秒。
     */
    @Test
    void timeoutSecondsCanBeOverridden() {
        LlmSettings settings = LlmSettings.fromEnvironment(Map.of("JASAGENT_LLM_TIMEOUT_SECONDS", "15"));
        assertEquals(Duration.ofSeconds(15), settings.timeout());
    }

    /**
     * 断言按 DeepSeek 配置建出的聊天模型使用 deepseek-flash 和官方根地址。
     */
    @Test
    void deepSeekModelNameAndBaseUrl() throws Exception {
        OpenAiChatModel model = LangChain4jModels.chat(OpenAiCallConfig.deepSeek(configured(false)));
        assertEquals("deepseek-flash", model.defaultRequestParameters().modelName());
        assertEquals("https://api.deepseek.com", clientBaseUrl(model));
    }

    /**
     * 思考开关关闭时，断言发给 DeepSeek 的自定义参数把思考标成 disabled。
     */
    @Test
    void deepSeekThinkingDisabledByDefault() {
        OpenAiChatModel model = LangChain4jModels.chat(OpenAiCallConfig.deepSeek(configured(false)));
        assertEquals("disabled", thinkingType(model));
    }

    /**
     * 客户端带回思考文本时，断言路由结果保留正文和思考，并把模型名改成实际使用的 deepseek-flash。
     */
    @Test
    void routerKeepsReasoningText() {
        Fixture fixture = open(settings("", false, false, ""), () -> false);
        fixture.deepseek.response = new LlmChatResponse("ignored", "ignored", "答案", 1, 2, 3, false, "先想一步");
        LlmChatResponse response = join(fixture.router.chat(chat(LlmTask.REACT, null, 1)));
        assertEquals("答案", response.text());
        assertEquals("先想一步", response.thinking());
        assertEquals("deepseek-flash", response.model());
    }

    /**
     * 环境把思考开关设为 true 时，断言 DeepSeek 请求参数里的思考类型是 enabled。
     */
    @Test
    void deepSeekThinkingEnabledFromEnvironment() {
        LlmSettings settings = LlmSettings.fromEnvironment(Map.of(
                "JASAGENT_DEEPSEEK_API_KEY", DEEPSEEK_KEY,
                "JASAGENT_DEEPSEEK_THINKING", "true"));
        OpenAiChatModel model = LangChain4jModels.chat(OpenAiCallConfig.deepSeek(settings));
        assertEquals("enabled", thinkingType(model));
    }

    /**
     * 断言 DeepSeek 模型把库重试次数设为 0，并且底层 HTTP 客户端类名里没有日志包装。
     */
    @Test
    void langchain4jDisablesLibraryRetryAndRequestLogs() throws Exception {
        OpenAiChatModel model = LangChain4jModels.chat(OpenAiCallConfig.deepSeek(configured(false)));
        assertEquals(0, reflectiveInt(model, "maxRetries"));
        Object client = reflective(model, "client");
        Object httpClient = reflective(client, "httpClient");
        assertFalse(httpClient.getClass().getName().contains("Logging"));
    }

    /**
     * 断言网关聊天模型使用配置的模型名和根地址，并且默认参数里没有思考字段。
     */
    @Test
    void gatewayChatModelUsesConfiguredEndpoint() throws Exception {
        OpenAiChatModel model = LangChain4jModels.chat(OpenAiCallConfig.gatewayChat(configured(false)));
        assertEquals(GATEWAY_MODEL, model.defaultRequestParameters().modelName());
        assertEquals("https://gateway.example", clientBaseUrl(model));
        OpenAiChatRequestParameters parameters = (OpenAiChatRequestParameters) model.defaultRequestParameters();
        Map<String, Object> custom = parameters.customParameters();
        assertTrue(custom == null || !custom.containsKey("thinking"));
    }

    /**
     * 读本模块源码和构建文件，断言聊天走 LangChain4j，而不是手写 HTTP 客户端或 Spring。
     */
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

    /**
     * 断言聊天阶段在完成前就是异步阶段，并且假客户端是在虚拟线程上被调用的。
     */
    @Test
    void chatRunsOnVirtualThread() {
        Fixture fixture = open(settings("", false, false, ""), () -> false);
        CompletionStage<LlmChatResponse> stage = fixture.router.chat(chat(LlmTask.REACT, null, 1));
        assertInstanceOf(CompletionStage.class, stage);
        join(stage);
        assertEquals(1, fixture.deepseek.virtual.size());
        assertTrue(fixture.deepseek.virtual.get(0));
    }

    /**
     * 网关不健康时，断言 REACT、压缩、重排和评判都走 DeepSeek flash，网关一次都没被调用。
     */
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

    /**
     * 请求指定 DeepSeek 上的具体模型时，断言只把该模型名发给 DeepSeek，健康网关不被调用。
     */
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

    /**
     * 请求指定网关模型时，断言只调用网关，并把该模型名传给网关客户端。
     */
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

    /**
     * 指定了网关模型但网关没配好时，断言失败，并且不回退到 DeepSeek。
     */
    @Test
    void unavailableGatewayChoiceFailsWithoutFallingBack() {
        Fixture fixture = open(blankGateway(), () -> false);
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(LlmTask.REACT, "ai-gateway", GATEWAY_MODEL, 1))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(0, fixture.deepseek.chats.size());
        assertEquals(0, fixture.gateway.chats.size());
    }

    /**
     * 断言 DeepSeek 客户端拒绝嵌入请求，并返回模型错误。
     */
    @Test
    void deepSeekClientRejectsEmbed() {
        LangChain4jLlmClient client = LangChain4jLlmClient.deepSeek(configured(false));
        AgentException error = assertThrows(AgentException.class,
                () -> join(client.embed(new LlmEmbedRequest("exec-1", "hello"))));
        assertEquals(ErrorCode.LLM, error.code());
    }

    /**
     * 让 DeepSeek 对指定任务失败，并断言网关没有被拿来补救。
     *
     * @param task 被测的模型任务
     */
    private static void assertTaskFailureDoesNotCallGateway(LlmTask task) {
        Fixture fixture = open(configured(false), () -> true);
        fixture.deepseek.failure = new AgentException(ErrorCode.LLM, "deepseek down");
        AgentException error = assertThrows(AgentException.class,
                () -> join(fixture.router.chat(chat(task, null, 2))));
        assertEquals(ErrorCode.LLM, error.code());
        assertEquals(0, fixture.gateway.chats.size());
    }

    /**
     * 断言响应来自 DeepSeek flash、没有回退，并且只有 DeepSeek 客户端收到了一次请求。
     *
     * @param response 路由器返回的聊天结果
     * @param fixture 这次测试的假客户端和记录
     */
    private static void assertDeepseek(LlmChatResponse response, Fixture fixture) {
        assertEquals("deepseek", response.provider());
        assertEquals("deepseek-flash", response.model());
        assertFalse(response.fallback());
        assertEquals(1, fixture.deepseek.chats.size());
        assertEquals(0, fixture.gateway.chats.size());
    }

    /**
     * 做一条固定执行编号和用户消息的聊天请求，不指定模型名。
     *
     * @param task 模型任务
     * @param provider 钉住的供应方；null 表示由路由自己选
     * @param remainingSteps 这次 REACT 还允许的步数
     * @return 可直接交给路由器的请求
     */
    private static LlmChatRequest chat(LlmTask task, String provider, int remainingSteps) {
        return new LlmChatRequest("exec-1", task, provider, List.of(new LlmMessage("user", "hello")), remainingSteps);
    }

    /**
     * 做一条同时写明供应方和模型名的聊天请求。
     *
     * @param task 模型任务
     * @param provider 钉住的供应方
     * @param model 指定要调用的模型名
     * @param remainingSteps 这次 REACT 还允许的步数
     * @return 带模型名的聊天请求
     */
    private static LlmChatRequest chat(LlmTask task, String provider, String model, int remainingSteps) {
        return new LlmChatRequest("exec-1", task, provider, List.of(new LlmMessage("user", "hello")), remainingSteps, model);
    }

    /**
     * 做一份网关地址只有空白、其余 DeepSeek 可用的配置。
     *
     * @return 网关地址不可用的设置
     */
    private static LlmSettings blankGateway() {
        return settings("   ", false, false, "");
    }

    /**
     * 做一份 DeepSeek 和网关聊天都已填好的配置，嵌入保持关闭。
     *
     * @param thinking 是否打开 DeepSeek 思考
     * @return 可用于聊天路由的设置
     */
    private static LlmSettings configured(boolean thinking) {
        return new LlmSettings(DEEPSEEK_KEY, thinking, "https://gateway.example", GATEWAY_KEY, GATEWAY_MODEL, false, "", Duration.ofSeconds(60));
    }

    /**
     * 按给定网关地址、思考开关和嵌入项组装测试配置。
     *
     * @param gatewayBase 网关根地址
     * @param thinking 是否打开 DeepSeek 思考
     * @param embeddings 是否打开网关嵌入
     * @param embeddingModel 网关嵌入模型名
     * @return 超时固定为 60 秒的设置
     */
    private static LlmSettings settings(String gatewayBase, boolean thinking, boolean embeddings, String embeddingModel) {
        return new LlmSettings(DEEPSEEK_KEY, thinking, gatewayBase, GATEWAY_KEY, GATEWAY_MODEL, embeddings, embeddingModel, Duration.ofSeconds(60));
    }

    /**
     * 做一份网关地址已填、只变化嵌入开关和嵌入模型名的配置。
     *
     * @param embeddings 是否打开网关嵌入
     * @param embeddingModel 网关嵌入模型名
     * @return 用于嵌入路由的设置
     */
    private static LlmSettings embeddingSettings(boolean embeddings, String embeddingModel) {
        return new LlmSettings(DEEPSEEK_KEY, false, "https://gateway.example", GATEWAY_KEY, GATEWAY_MODEL, embeddings, embeddingModel, Duration.ofSeconds(60));
    }

    /**
     * 用两份假客户端、内存记录和内存追踪装好被测路由器。
     *
     * @param settings 这次路由使用的模型配置
     * @param health 网关健康检查的结果
     * @return 路由器和可断言的替身
     */
    /**
     * 点名 grok 时只调用 Grok 客户端，不调用 DeepSeek，也不调用网关。
     */
    @Test
    void namedGrokDoesNotCallDeepseek() {
        LlmSettings settings = new LlmSettings(
                DEEPSEEK_KEY,
                false,
                "https://gateway.example",
                GATEWAY_KEY,
                GATEWAY_MODEL,
                false,
                "",
                Duration.ofSeconds(60),
                "grok-test-key");
        FakeLlmClient deepseek = new FakeLlmClient();
        FakeLlmClient gateway = new FakeLlmClient();
        FakeLlmClient grok = new FakeLlmClient();
        grok.response = new LlmChatResponse("ignored", "ignored", "from-grok", 1, 1, 2, false);
        LlmRouter router = new LlmRouter(settings, () -> true, deepseek, gateway, grok, new MemoryStore(), new MemoryTrace());
        LlmChatResponse response = join(router.chat(chat(LlmTask.REACT, "grok", 1)));
        assertEquals("grok", response.provider());
        assertEquals(OpenAiCallConfig.GROK_MODEL, response.model());
        assertEquals("from-grok", response.text());
        assertEquals(0, deepseek.chats.size());
        assertEquals(0, gateway.chats.size());
        assertEquals(1, grok.chats.size());
    }

    /**
     * 自动路由在网关不可用时仍走 DeepSeek，不会因为多了 Grok 客户端就改道。
     */
    @Test
    void automaticRouteStillUsesDeepseekWhenGrokIsConfigured() {
        LlmSettings settings = new LlmSettings(
                DEEPSEEK_KEY,
                false,
                "   ",
                GATEWAY_KEY,
                GATEWAY_MODEL,
                false,
                "",
                Duration.ofSeconds(60),
                "grok-test-key");
        FakeLlmClient deepseek = new FakeLlmClient();
        FakeLlmClient gateway = new FakeLlmClient();
        FakeLlmClient grok = new FakeLlmClient();
        LlmRouter router = new LlmRouter(settings, () -> false, deepseek, gateway, grok, new MemoryStore(), new MemoryTrace());
        LlmChatResponse response = join(router.chat(chat(LlmTask.REACT, null, 1)));
        assertEquals("deepseek", response.provider());
        assertEquals(OpenAiCallConfig.DEEPSEEK_MODEL, response.model());
        assertEquals(1, deepseek.chats.size());
        assertEquals(0, grok.chats.size());
    }

    private static Fixture open(LlmSettings settings, GatewayHealth health) {
        FakeLlmClient deepseek = new FakeLlmClient();
        FakeLlmClient gateway = new FakeLlmClient();
        MemoryStore store = new MemoryStore();
        MemoryTrace trace = new MemoryTrace();
        LlmRouter router = new LlmRouter(settings, health, deepseek, gateway, store, trace);
        return new Fixture(router, deepseek, gateway, store, trace);
    }

    /**
     * 从模型默认参数的 thinking 对象里取出 type。
     *
     * @param model 已按 DeepSeek 配置建好的聊天模型
     * @return thinking.type 的文本，例如 enabled 或 disabled
     */
    private static String thinkingType(OpenAiChatModel model) {
        OpenAiChatRequestParameters parameters = (OpenAiChatRequestParameters) model.defaultRequestParameters();
        Object value = parameters.customParameters().get("thinking");
        @SuppressWarnings("unchecked")
        Map<String, Object> thinking = (Map<String, Object>) value;
        return String.valueOf(thinking.get("type"));
    }

    /**
     * 用反射读取模型内部客户端的根地址。
     *
     * @param model 要检查的聊天模型
     * @return 客户端实际保存的 baseUrl
     */
    private static String clientBaseUrl(OpenAiChatModel model) throws Exception {
        Object client = reflective(model, "client");
        return String.valueOf(reflective(client, "baseUrl"));
    }

    /**
     * 用反射读取一个整型字段。
     *
     * @param target 持有该字段的对象
     * @param name 字段名
     * @return 字段的整数值
     */
    private static int reflectiveInt(Object target, String name) throws Exception {
        return ((Number) reflective(target, name)).intValue();
    }

    /**
     * 用反射读取私有字段，供测试核对客户端库的实际配置。
     *
     * @param target 持有该字段的对象
     * @param name 字段名
     * @return 字段当前的值
     */
    private static Object reflective(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * 把本模块 main 源码拼成一段文本，用来检查没有引入禁用的客户端写法。
     *
     * @return 所有主源码按文件拼起来的文本
     */
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

    /**
     * 在 30 秒内等到阶段结束。运行时异常和错误原样抛出，中断会恢复中断标记。
     *
     * @param <T> 阶段结果的类型
     * @param stage 要等待的异步阶段
     * @return 阶段成功时的结果
     */
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

    /**
     * 一次路由测试用到的路由器和它背后的假客户端、内存记录、内存追踪。
     *
     * @param router 被测的路由器
     * @param deepseek 假的 DeepSeek 客户端
     * @param gateway 假的网关客户端
     * @param store 记下的模型调用
     * @param trace 记下的追踪行
     */
    private record Fixture(LlmRouter router, FakeLlmClient deepseek, FakeLlmClient gateway, MemoryStore store, MemoryTrace trace) {
    }

    /**
     * 按脚本记下聊天和嵌入请求，先消耗预设的超时次数，再抛出预设失败或返回固定结果。
     */
    static final class FakeLlmClient implements LlmClient {
        final List<LlmChatRequest> chats = new ArrayList<>();
        final List<LlmEmbedRequest> embeds = new ArrayList<>();
        final List<Boolean> virtual = new ArrayList<>();
        int timeouts;
        RuntimeException failure;
        LlmChatResponse response = new LlmChatResponse("ignored", "ignored", "answer", 11, 7, 18, false);

        /**
         * 记下这次聊天是否跑在虚拟线程。还有剩余超时时抛出超时，否则抛出预设失败或返回固定回答。
         *
         * @param request 路由器发来的聊天请求
         * @return 立即完成并带固定回答的阶段；超时和失败时直接抛出
         */
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

        /**
         * 记下嵌入请求和调用线程，并返回一段固定向量。
         *
         * @param request 路由器发来的嵌入请求
         * @return 立即完成、向量来自假客户端的嵌入结果
         */
        @Override
        public CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request) {
            virtual.add(Thread.currentThread().isVirtual());
            embeds.add(request);
            return CompletableFuture.completedFuture(new LlmEmbedResponse("from-client", "from-client", new float[] {0.25f}));
        }
    }

    /**
     * 把模型调用留在内存列表里，供断言核对供应方、模型和 token。
     */
    static final class MemoryStore implements LlmCallStore {
        final List<LlmCall> calls = new CopyOnWriteArrayList<>();

        /**
         * 把一条模型调用追加到内存列表。
         *
         * @param call 路由器写来的调用记录
         * @return 立即完成的空阶段
         */
        @Override
        public CompletionStage<Void> save(LlmCall call) {
            calls.add(call);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 把追踪行留在内存里，并能拼成一整段文本供断言搜索。
     */
    static final class MemoryTrace implements LlmTrace {
        final List<String> lines = new CopyOnWriteArrayList<>();

        /**
         * 追加一行追踪。执行编号只随调用传入，列表里保存的是这一行正文。
         *
         * @param executionId 这次执行的编号
         * @param line 已经交给追踪出口的一行
         * @return 立即完成的空阶段
         */
        @Override
        public CompletionStage<Void> write(String executionId, String line) {
            lines.add(line);
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 把已写入的追踪行用换行拼成一段文本。
         *
         * @return 按写入顺序拼接的追踪正文
         */
        String text() {
            return String.join("\n", lines);
        }
    }
}
