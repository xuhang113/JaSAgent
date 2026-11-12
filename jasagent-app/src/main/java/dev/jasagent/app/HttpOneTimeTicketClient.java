package dev.jasagent.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.pass.OneTimeTicket;
import dev.jasagent.api.pass.OneTimeTicketClient;
import dev.jasagent.api.pass.TicketRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向本机 agentpass 领取一次性证。失败、超时或返回空白证时给出无效证，不把请求体写进异常。
 */
public final class HttpOneTimeTicketClient implements OneTimeTicketClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI endpoint;
    private final HttpClient http;

    /**
     * 连到给定的领证地址，超时两秒。
     *
     * @param endpoint 领证地址，例如 http://127.0.0.1:18741/v1/tickets
     */
    public HttpOneTimeTicketClient(String endpoint) {
        this.endpoint = URI.create(endpoint);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /**
     * 提交租户、用户、会话、执行、目标和工具名。响应里 ticket 非空才算领到。
     *
     * @param request 这次外部调用的身份和目标
     * @return 有效证或空白证
     */
    @Override
    public OneTimeTicket acquire(TicketRequest request) {
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("tenantId", text(request.tenantId()));
            body.put("userId", text(request.userId()));
            body.put("sessionId", text(request.sessionId()));
            body.put("executionId", text(request.executionId()));
            body.put("target", text(request.target()));
            body.put("toolName", text(request.toolName()));
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new OneTimeTicket("");
            }
            JsonNode root = JSON.readTree(response.body() == null ? "" : response.body());
            String ticket = root.path("ticket").asText("");
            return new OneTimeTicket(ticket);
        } catch (Exception ex) {
            return new OneTimeTicket("");
        }
    }

    /**
     * 把空引用收成空串，避免 JSON 里出现 null。
     *
     * @param value 原始字段
     * @return 非空文本
     */
    private static String text(String value) {
        return value == null ? "" : value;
    }
}
