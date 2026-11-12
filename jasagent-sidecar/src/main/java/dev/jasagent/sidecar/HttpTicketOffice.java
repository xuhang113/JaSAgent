package dev.jasagent.sidecar;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用 JDK HTTP 客户端调用已有 agentpass 的校验和核销接口。
 * 不跟随重定向，避免证明文被转到别的主机。网络失败、超时或正文无法解析时返回不可达，调用方不转发。
 * 日志只记异常类型，不记异常消息，因为消息里可能带上请求体。
 */
public final class HttpTicketOffice implements TicketOffice, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HttpTicketOffice.class);
    private final String baseUrl;
    private final long timeoutMillis;
    private final HttpClient http;
    private final ObjectMapper mapper;

    /**
     * 按根地址建立不跟随重定向的客户端。此时不拨号。
     *
     * @param baseUrl 通行证服务根地址，例如 {@code http://127.0.0.1:18741}
     * @param timeoutMillis 连接和读取的超时毫秒数
     */
    public HttpTicketOffice(String baseUrl, long timeoutMillis) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.timeoutMillis = Math.max(1, timeoutMillis);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 调用 {@code POST /v1/tickets/verify}。非 200 或无法解析时视为不可达。
     *
     * @param call 绑定字段，目标是即将转发的规范地址
     * @param ticket 证明文
     * @return 校验报告
     */
    @Override
    public TicketVerdict verify(TicketCall call, String ticket) {
        Exchange exchange = exchange(EgressPaths.VERIFY, call, ticket);
        if (exchange == null || exchange.status != 200) {
            return TicketVerdict.unreachable();
        }
        try {
            return mapper.readValue(exchange.body, TicketVerdict.class);
        } catch (Exception ex) {
            log.info("agentpass verify unreadable kind={}", ex.getClass().getSimpleName());
            return TicketVerdict.unreachable();
        }
    }

    /**
     * 调用 {@code POST /v1/tickets/redeem}。只有 HTTP 200 且 {@code redeemed} 为真才算核销成功。
     * 成功响应里 {@code used} 也会为真，调用方不能据此把第一次成功当成已用拒绝。
     *
     * @param call 绑定字段
     * @param ticket 证明文
     * @return 核销结果
     */
    @Override
    public TicketRedeem redeem(TicketCall call, String ticket) {
        Exchange exchange = exchange(EgressPaths.REDEEM, call, ticket);
        if (exchange == null) {
            return TicketRedeem.unreachable();
        }
        try {
            JsonNode node = mapper.readTree(exchange.body);
            TicketVerdict verdict = mapper.treeToValue(node, TicketVerdict.class);
            boolean redeemed = exchange.status == 200 && node.path("redeemed").asBoolean(false);
            return new TicketRedeem(redeemed, verdict);
        } catch (Exception ex) {
            log.info("agentpass redeem unreadable kind={}", ex.getClass().getSimpleName());
            return TicketRedeem.unreachable();
        }
    }

    /**
     * 关闭底层客户端，释放选择器和连接。重复关闭没有额外作用。
     */
    @Override
    public void close() {
        http.close();
    }

    /**
     * 向通行证服务发送 JSON。证明文只出现在请求体，不出现在地址或日志里。
     * 连接失败、超时或根地址为空时返回空。
     *
     * @param path {@link EgressPaths#VERIFY} 或 {@link EgressPaths#REDEEM}
     * @param call 绑定字段
     * @param ticket 证明文
     * @return 状态码和正文；失败时为空
     */
    private Exchange exchange(String path, TicketCall call, String ticket) {
        if (baseUrl.isEmpty() || call == null || ticket == null || ticket.isBlank()) {
            return null;
        }
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("ticket", ticket);
            payload.put("tenantId", text(call.tenantId()));
            payload.put("userId", text(call.userId()));
            payload.put("sessionId", text(call.sessionId()));
            payload.put("executionId", text(call.executionId()));
            payload.put("target", text(call.target()));
            payload.put("toolName", text(call.toolName()));
            HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Exchange(response.statusCode(), response.body() == null ? "" : response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.info("agentpass call interrupted kind={}", ex.getClass().getSimpleName());
            return null;
        } catch (Exception ex) {
            log.info("agentpass call failed kind={}", ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 把根地址和路径拼成绝对 URI，并去掉根地址末尾多余的斜线。
     *
     * @param path 以斜线开头的接口路径
     * @return 请求 URI
     */
    private URI endpoint(String path) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(trimmed + path);
    }

    /**
     * 把空引用收成空串，避免 JSON 里出现 null。空白字段在 agentpass 侧表示不额外对照。
     *
     * @param value 绑定字段原文
     * @return 非空文本
     */
    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * 一次 HTTP 交换的状态码和正文。正文可能含报告，但不会被日志打印。
     *
     * @param status HTTP 状态码
     * @param body 响应正文，不会是空引用
     */
    private record Exchange(int status, String body) {
    }
}
