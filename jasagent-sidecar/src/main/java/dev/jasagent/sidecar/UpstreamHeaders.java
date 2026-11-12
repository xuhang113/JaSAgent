package dev.jasagent.sidecar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 决定哪些请求头可以跟到上游。通行证、身份头和逐跳头留在 sidecar，授权头可以跟去，因为大模型调用需要它。
 * 跟过去的头仍然不能写进日志。
 */
final class UpstreamHeaders {
    /**
     * JDK 客户端会自己填写、或会改变连接语义的头。再设置一次会抛错，或者把 sidecar 的连接信息带给上游。
     */
    private static final Set<String> HOP = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length",
            "expect");

    /**
     * 禁止实例化。过滤只通过 {@link #outbound(Map)} 完成。
     */
    private UpstreamHeaders() {
    }

    /**
     * 复制可以送到上游的头。名字以 {@code x-jasagent-} 开头的控制头全部去掉，值里含换行的头也去掉。
     *
     * @param headers 调用方原来的头，可以是空
     * @return 新的头映射；没有可送的头时为空映射
     */
    static Map<String, List<String>> outbound(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank() || drop(name) || entry.getValue() == null) {
                continue;
            }
            List<String> values = entry.getValue().stream().filter(UpstreamHeaders::safeValue).toList();
            if (!values.isEmpty()) {
                copy.put(name, List.copyOf(values));
            }
        }
        return Map.copyOf(copy);
    }

    /**
     * 判断一个头是不是不能送到上游。比较时忽略大小写。
     *
     * @param name 头的名字
     * @return 逐跳头或 jasagent 控制头时为真
     */
    private static boolean drop(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return HOP.contains(lower) || lower.startsWith("x-jasagent-");
    }

    /**
     * 判断头的值能不能安全地交给 HTTP 客户端。换行会把一个头拆成两个，所以直接丢掉。
     *
     * @param value 头的一个值
     * @return 非空且不含回车或换行时为真
     */
    private static boolean safeValue(String value) {
        return value != null && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }
}
