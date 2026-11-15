package dev.jasagent.app;

import dev.jasagent.llm.egress.SidecarForward;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 在领到一次性证之后访问示例检索服务。证放在请求头里，不写入异常说明。
 * 本机和集群内的检索地址直连；离开集群时才经 sidecar，避免把集群内服务的证交给转发层核销。
 */
public final class RemoteRagClient {
    private final SidecarForward http = SidecarForward.open(Duration.ofSeconds(2), false);

    /**
     * 向检索服务提交查询。地址无效或调用失败时返回说明，不包含证文本。
     *
     * @param baseUrl 检索服务根地址，例如 http://127.0.0.1:18751
     * @param query 检索原文
     * @param ticket 一次性证
     * @return 服务正文；失败时是固定说明
     */
    public String search(String baseUrl, String query, String ticket) {
        return search(baseUrl, query, ticket, "", "", "");
    }

    /**
     * 向检索服务提交查询，并带上这次执行和会话，供命中写入溯源。证仍只放在请求头。
     *
     * @param baseUrl 检索服务根地址，例如 http://127.0.0.1:18751
     * @param query 检索原文
     * @param ticket 一次性证
     * @param executionId 这次执行，空白时检索服务不记溯源
     * @param sessionId 会话编号
     * @param answerId 回答编号，空白时由检索服务改用执行编号
     * @return 服务正文；失败时是固定说明
     */
    public String search(String baseUrl, String query, String ticket, String executionId, String sessionId, String answerId) {
        try {
            String payload = "{\"query\":" + quote(query)
                    + ",\"executionId\":" + quote(executionId)
                    + ",\"sessionId\":" + quote(sessionId)
                    + ",\"answerId\":" + quote(answerId) + "}";
            URI uri = URI.create(baseUrl.endsWith("/") ? baseUrl + "search" : baseUrl + "/search");
            try (SidecarForward.Call response = http.exchange("POST", uri, Map.of(
                    "Content-Type", List.of("application/json"),
                    "X-Jasagent-Ticket", List.of(ticket == null ? "" : ticket)),
                    payload.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5))) {
                String text = response.text();
                if (response.rejected(text) || response.status() < 200 || response.status() >= 300) {
                    return "检索服务拒绝了这次调用";
                }
                return text;
            }
        } catch (Exception ex) {
            return "检索服务不可达";
        }
    }

    /**
     * 把查询收成 JSON 字符串字面量。
     *
     * @param value 检索原文，可以是 null
     * @return 带引号的 JSON 字符串
     */
    private static String quote(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
