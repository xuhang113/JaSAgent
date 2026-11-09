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

/**
 * 按 DeepSeek 的模型列表接口取出可用模型 id，并在一分钟内复用上次成功或回退的结果。
 */
final class DeepSeekModelDirectory implements SessionModelCatalog.DeepSeekModels {
    /**
     * 默认的 DeepSeek 模型列表地址，由官方对话基址拼上 /models。
     */
    static final URI MODELS = URI.create(OpenAiCallConfig.DEEPSEEK_BASE_URL + "/models");
    /**
     * 解析模型列表响应的 JSON 读取器，只读 data 数组里的 id。
     */
    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * 连接和单次拉取模型列表共用的 4 秒上限，避免目录查询把会话接口拖住。
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    private final URI endpoint;
    private final HttpClient client;
    private volatile List<String> cached = List.of();
    private volatile long cachedAt;

    /**
     * 用默认的 DeepSeek 模型列表地址构造目录。
     * 副作用：创建连接超时为 4 秒的 HTTP 客户端。
     */
    DeepSeekModelDirectory() {
        this(MODELS);
    }

    /**
     * 用指定的模型列表地址构造目录，测试可以把地址指到本机桩。
     * 副作用：保存地址并创建连接超时为 4 秒的 HTTP 客户端。
     *
     * @param endpoint 返回模型列表 JSON 的完整地址
     */
    DeepSeekModelDirectory(URI endpoint) {
        this.endpoint = endpoint;
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * 返回该密钥能看到的模型 id。密钥为空时不发请求。一分钟内若已有非空缓存则直接复用，否则重新拉取并覆盖缓存。
     *
     * @param apiKey DeepSeek 的 Bearer 密钥
     * @return 模型 id；密钥为空时是空列表，拉取失败时至少包含默认的 deepseek-flash
     */
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

    /**
     * 向模型列表地址发 GET。非 2xx、网络失败或解析结果为空时，只返回默认模型，避免目录整体消失。线程被中断时恢复中断标记。
     *
     * @param apiKey 放进 Authorization 的 Bearer 密钥
     * @return 解析出的 id；失败时只有 deepseek-flash
     */
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

    /**
     * 从模型列表 JSON 的 data 数组收集 id，去掉两端空白，跳过空 id。正文不是对象数组时返回空列表，由调用方决定是否回退。
     *
     * @param body 接口返回的原始正文，可以是空
     * @return 按出现顺序排列的模型 id；无法解析时为空列表
     */
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
