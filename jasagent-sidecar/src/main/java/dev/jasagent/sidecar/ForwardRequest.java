package dev.jasagent.sidecar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 调用方交给 sidecar 的一次出站请求。通行证和身份只放在字段里，真正拨号前会从头映射里去掉。
 * 正文按字节保存，不假定编码，也不写进日志。
 *
 * @param method 调用方的 HTTP 方法，构造时收成大写
 * @param url {@code X-Jasagent-Url} 的原文，还没做规范化和主机判断
 * @param headers 调用方带来的头，可能仍含通行证；送到上游之前会再滤一遍
 * @param body 要转发给上游的正文，空引用会收成空数组
 * @param ticket 证明文；免证主机可以是空串。本字段不进入日志
 * @param tenantId 声称的租户，空白表示不额外对照
 * @param userId 声称的登录名，空白表示不额外对照
 * @param sessionId 声称的会话编号，不是登录令牌
 * @param executionId 声称的执行编号
 * @param toolName 声称的工具名，空白表示不额外对照
 */
public record ForwardRequest(
        String method,
        String url,
        Map<String, List<String>> headers,
        byte[] body,
        String ticket,
        String tenantId,
        String userId,
        String sessionId,
        String executionId,
        String toolName) {

    /**
     * 把空引用收成空值，并复制头和正文，避免调用方事后改动影响已经收下的请求。
     * 通行证只做两端空白裁剪，不记录裁剪前后的内容。
     *
     * @param method 方法原文
     * @param url 目的地址原文
     * @param headers 原始头
     * @param body 原始正文
     * @param ticket 证明文
     * @param tenantId 租户
     * @param userId 登录名
     * @param sessionId 会话编号
     * @param executionId 执行编号
     * @param toolName 工具名
     */
    public ForwardRequest {
        method = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        url = url == null ? "" : url.trim();
        headers = copyHeaders(headers);
        body = body == null ? new byte[0] : body.clone();
        ticket = ticket == null ? "" : ticket.trim();
        tenantId = clean(tenantId);
        userId = clean(userId);
        sessionId = clean(sessionId);
        executionId = clean(executionId);
        toolName = clean(toolName);
    }

    /**
     * 复制头映射。空名字和空值列表丢掉，值列表本身也复制，避免外部修改。
     *
     * @param headers 原始头，可以是空
     * @return 不可变的头映射
     */
    private static Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            List<String> values = entry.getValue().stream().filter(value -> value != null).toList();
            if (!values.isEmpty()) {
                copy.put(entry.getKey(), List.copyOf(values));
            }
        }
        return Map.copyOf(copy);
    }

    /**
     * 把身份字段收成去掉两端空白的文本。没有内容时为空串，agentpass 会把它看成不额外对照。
     *
     * @param value 头或参数原文
     * @return 非空文本
     */
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
