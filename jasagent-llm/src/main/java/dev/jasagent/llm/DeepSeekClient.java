package dev.jasagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.ModelClient;
import dev.jasagent.api.llm.TokenUsage;
import dev.jasagent.api.text.SecretRedactor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class DeepSeekClient implements ModelClient {
    public static final URI DEFAULT_BASE = URI.create("https://api.deepseek.com");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final URI base;
    private final HttpClient http;
    private final Consumer<String> log;

    public DeepSeekClient(Duration ignoredTimeout, String apiKey, URI base, HttpClient http, Consumer<String> log) {
        this.apiKey = apiKey;
        this.base = base == null ? DEFAULT_BASE : base;
        this.http = http == null ? HttpClient.newHttpClient() : http;
        this.log = log;
    }

    public static DeepSeekClient fromEnvironment(Consumer<String> log) {
        LlmSettings settings = LlmSettings.from(System.getenv(), java.util.Map.of());
        return new DeepSeekClient(settings.timeout(), settings.deepseekApiKey(), DEFAULT_BASE, HttpClient.newHttpClient(), log);
    }

    @Override
    public String provider() {
        return "deepseek";
    }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public boolean supportsEmbeddings() {
        return false;
    }

    @Override
    public CompletionStage<ChatResponse> chat(String model, ChatRequest request, Duration timeout, boolean thinking) {
        return VirtualIo.supply(() -> send(request, timeout, thinking));
    }

    @Override
    public CompletionStage<float[]> embed(String text, String model, Duration timeout) {
        return VirtualIo.supply(() -> {
            throw new AgentException(ErrorCode.LLM, "embed 不走 DeepSeek");
        });
    }

    private ChatResponse send(ChatRequest request, Duration timeout, boolean thinking) {
        if (!available()) {
            throw new AgentException(ErrorCode.LLM, "缺少 DeepSeek 密钥", request.executionId(), null);
        }
        String body = ChatPayloads.deepseek(request.messages(), request.tools(), thinking);
        if (log != null) {
            log.accept(SecretRedactor.redact("Authorization: Bearer " + apiKey + "\n" + body));
        }
        Duration limit = timeout == null ? Duration.ofSeconds(60) : timeout;
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(base.resolve("/chat/completions"))
                .timeout(limit)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AgentException(ErrorCode.LLM, "DeepSeek 返回 " + response.statusCode(), request.executionId(), null);
            }
            return parse(response.body(), request);
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.LLM, "DeepSeek 调用失败", request.executionId(), ex);
        }
    }

    private ChatResponse parse(String body, ChatRequest request) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode message = root.path("choices").path(0).path("message");
        String content = message.path("content").asText("");
        JsonNode usage = root.path("usage");
        return new ChatResponse(
                "deepseek",
                "deepseek-flash",
                ChatMessage.text(message.path("role").asText("assistant"), content),
                new TokenUsage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0)),
                0,
                false);
    }
}
