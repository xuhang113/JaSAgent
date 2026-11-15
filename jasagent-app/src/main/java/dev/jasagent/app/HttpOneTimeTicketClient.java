package dev.jasagent.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.pass.OneTimeTicket;
import dev.jasagent.api.pass.OneTimeTicketClient;
import dev.jasagent.api.pass.TicketRequest;

import dev.jasagent.llm.egress.SidecarForward;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向本机 agentpass 领取一次性证。失败、超时或返回空白证时给出无效证，不把请求体写进异常。
 * agentpass 在本集群内，领证请求直连，不经 sidecar。
 */
public final class HttpOneTimeTicketClient implements OneTimeTicketClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private final URI endpoint;
    private final SidecarForward http;

    /**
     * 连到给定的领证地址，超时两秒。
     *
     * @param endpoint 领证地址，例如 http://127.0.0.1:18741/v1/tickets
     */
    public HttpOneTimeTicketClient(String endpoint) {
        this.endpoint = URI.create(endpoint);
        this.http = SidecarForward.open(TIMEOUT, false);
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
            byte[] payload = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            try (SidecarForward.Call response = http.exchange("POST", endpoint, Map.of(
                    "Content-Type", List.of("application/json")), payload, TIMEOUT)) {
                String text = response.text();
                if (response.rejected(text) || response.status() < 200 || response.status() >= 300) {
                    return new OneTimeTicket("");
                }
                JsonNode root = JSON.readTree(text);
                String ticket = root.path("ticket").asText("");
                return new OneTimeTicket(ticket);
            }
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
