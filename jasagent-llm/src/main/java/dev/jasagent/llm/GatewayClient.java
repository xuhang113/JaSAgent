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

public final class GatewayClient implements ModelClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final URI base;
    private final HttpClient http;
    private final Consumer<String> log;
    private final boolean embeddings;

    public GatewayClient(String apiKey, URI base, boolean embeddings, HttpClient http, Consumer<String> log) {
        this.apiKey = apiKey;
        this.base = base;
        this.embeddings = embeddings;
        this.http = http == null ? HttpClient.newHttpClient() : http;
        this.log = log;
    }

    @Override
    public String provider() {
        return "ai-gateway";
    }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank() && base != null;
    }

    @Override
    public boolean supportsEmbeddings() {
        return embeddings;
    }

    @Override
    public CompletionStage<ChatResponse> chat(String model, ChatRequest request, Duration timeout, boolean thinking) {
        return VirtualIo.supply(() -> sendChat(model, request, timeout));
    }

    @Override
    public CompletionStage<float[]> embed(String text, String model, Duration timeout) {
        return VirtualIo.supply(() -> sendEmbed(text, model, timeout));
    }

    private ChatResponse sendChat(String model, ChatRequest request, Duration timeout) {
        if (!available()) {
            throw new AgentException(ErrorCode.LLM, "Gateway 未配置", request.executionId(), null);
        }
        String body = ChatPayloads.gateway(model, request.messages(), request.tools());
        if (log != null) {
            log.accept(SecretRedactor.redact("Authorization: Bearer " + apiKey + "\n" + body));
        }
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(endpoint("/chat/completions"))
                .timeout(timeout == null ? Duration.ofSeconds(60) : timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AgentException(ErrorCode.LLM, "Gateway 返回 " + response.statusCode(), request.executionId(), null);
            }
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode message = root.path("choices").path(0).path("message");
            JsonNode usage = root.path("usage");
            return new ChatResponse(
                    "ai-gateway",
                    model,
                    ChatMessage.text(message.path("role").asText("assistant"), message.path("content").asText("")),
                    new TokenUsage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0)),
                    0,
                    false);
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.LLM, "Gateway 调用失败", request.executionId(), ex);
        }
    }

    private float[] sendEmbed(String text, String model, Duration timeout) {
        if (!embeddings || !available()) {
            throw new AgentException(ErrorCode.LLM, "Gateway 没有 embeddings");
        }
        String body = MAPPER.createObjectNode()
                .put("model", model == null ? "" : model)
                .put("input", text == null ? "" : text)
                .toString();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(endpoint("/embeddings"))
                .timeout(timeout == null ? Duration.ofSeconds(60) : timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AgentException(ErrorCode.LLM, "Gateway embeddings 返回 " + response.statusCode());
            }
            JsonNode vector = MAPPER.readTree(response.body()).path("data").path(0).path("embedding");
            float[] values = new float[vector.size()];
            for (int index = 0; index < vector.size(); index++) {
                values[index] = (float) vector.get(index).asDouble();
            }
            return values;
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.LLM, "Gateway embeddings 调用失败", null, ex);
        }
    }

    private URI endpoint(String path) {
        String text = base.toString();
        if (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return URI.create(text + path);
    }
}
