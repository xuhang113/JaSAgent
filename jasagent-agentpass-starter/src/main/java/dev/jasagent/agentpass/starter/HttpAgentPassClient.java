package dev.jasagent.agentpass.starter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用 JDK HTTP 客户端调用通行证服务的校验和核销接口。不跟随重定向，也不记录证明文。
 * 网络失败、超时或正文无法解析时返回不可达，由过滤器拒绝代理流量。
 */
public final class HttpAgentPassClient implements AgentPassClient {
    private final AgentPassFilterProperties properties;
    private final HttpClient http;
    private final ObjectMapper mapper;

    /**
     * 按配置建立不跟随重定向的客户端，并允许响应里多出过滤器不关心的字段。
     *
     * @param properties 根地址和超时
     */
    public HttpAgentPassClient(AgentPassFilterProperties properties) {
        this.properties = properties;
        long timeout = Math.max(1, properties.getClientTimeoutMillis());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeout))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 调用 {@code POST /v1/tickets/verify}。非 200 或无法解析时视为不可达。
     *
     * @param call 声称的绑定字段
     * @param pass 证明文
     * @return 校验报告
     */
    @Override
    public VerifyReport verify(PassCall call, String pass) {
        String body = send("/v1/tickets/verify", call, pass);
        if (body == null) {
            return VerifyReport.unreachable();
        }
        try {
            return mapper.readValue(body, VerifyReport.class);
        } catch (Exception ex) {
            return VerifyReport.unreachable();
        }
    }

    /**
     * 调用 {@code POST /v1/tickets/redeem}。只有 HTTP 200 且 {@code redeemed} 为真才算核销成功。
     *
     * @param call 声称的绑定字段
     * @param pass 证明文
     * @return 核销结果
     */
    @Override
    public RemoteRedeem redeem(PassCall call, String pass) {
        Exchange exchange = exchange("/v1/tickets/redeem", call, pass);
        if (exchange == null) {
            return RemoteRedeem.unreachable();
        }
        try {
            JsonNode node = mapper.readTree(exchange.body());
            VerifyReport report = mapper.treeToValue(node, VerifyReport.class);
            boolean redeemed = exchange.status() == 200 && node.path("redeemed").asBoolean(false);
            return new RemoteRedeem(redeemed, report);
        } catch (Exception ex) {
            return RemoteRedeem.unreachable();
        }
    }

    /**
     * 发送校验请求并在状态为 200 时返回正文。其他情况返回空，调用方按不可达处理。
     *
     * @param path 以斜线开头的接口路径
     * @param call 绑定字段
     * @param pass 证明文
     * @return 响应正文；失败时为空
     */
    private String send(String path, PassCall call, String pass) {
        Exchange exchange = exchange(path, call, pass);
        if (exchange == null || exchange.status() != 200) {
            return null;
        }
        return exchange.body();
    }

    /**
     * 向通行证服务发送 JSON。不记录请求体。连接失败、超时或地址为空时返回空。
     *
     * @param path 接口路径
     * @param call 绑定字段
     * @param pass 证明文
     * @return 状态码和正文；失败时为空
     */
    private Exchange exchange(String path, PassCall call, String pass) {
        String base = properties.getClientBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("ticket", pass);
            payload.put("tenantId", call.tenant());
            payload.put("userId", call.user());
            payload.put("sessionId", call.sessionId());
            payload.put("executionId", call.executionId());
            payload.put("target", call.targetService());
            payload.put("toolName", call.toolName());
            HttpRequest request = HttpRequest.newBuilder(endpoint(base, path))
                    .timeout(Duration.ofMillis(Math.max(1, properties.getClientTimeoutMillis())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Exchange(response.statusCode(), response.body() == null ? "" : response.body());
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 把根地址和路径拼成绝对 URI，并去掉根地址末尾多余的斜线。
     *
     * @param base 通行证服务根地址
     * @param path 以斜线开头的路径
     * @return 请求 URI
     */
    private static URI endpoint(String base, String path) {
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmed + path);
    }

    /**
     * 一次 HTTP 交换的状态码和正文。
     *
     * @param status HTTP 状态码
     * @param body 响应正文，不会是空引用
     */
    private record Exchange(int status, String body) {
    }
}
