package dev.jasagent.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.llm.OpenAiCallConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class DeepSeekModelDirectory implements SessionModelCatalog.DeepSeekModels {
    static final URI MODELS = URI.create(OpenAiCallConfig.DEEPSEEK_BASE_URL + "/models");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    private final URI endpoint;
    private final HttpClient client;
    private volatile List<String> cached = List.of();
    private volatile long cachedAt;

    DeepSeekModelDirectory() {
        this(MODELS);
    }

    DeepSeekModelDirectory(URI endpoint) {
        this.endpoint = endpoint;
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public List<String> ids(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<String> current = cached;
        if (!current.isEmpty() && now - cachedAt < 60_000) {
            return current;
        }
        List<String> loaded = load(apiKey);
        cached = loaded;
        cachedAt = now;
        return loaded;
    }

    private List<String> load(String apiKey) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return List.of(SessionModelCatalog.DEFAULT_MODEL);
            }
            List<String> parsed = parse(response.body());
            return parsed.isEmpty() ? List.of(SessionModelCatalog.DEFAULT_MODEL) : parsed;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return List.of(SessionModelCatalog.DEFAULT_MODEL);
        } catch (Exception ex) {
            return List.of(SessionModelCatalog.DEFAULT_MODEL);
        }
    }

    static List<String> parse(String body) {
        List<String> ids = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return ids;
        }
        try {
            JsonNode data = JSON.readTree(body).get("data");
            if (data == null || !data.isArray()) {
                return ids;
            }
            for (JsonNode item : data) {
                JsonNode id = item.get("id");
                if (id != null && !id.asText().isBlank()) {
                    ids.add(id.asText().trim());
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return ids;
    }
}
