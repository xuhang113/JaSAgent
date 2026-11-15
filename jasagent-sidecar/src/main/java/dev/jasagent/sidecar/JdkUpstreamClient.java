package dev.jasagent.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用 JDK HTTP 客户端访问已经获准的上游。请求体和响应体都按流传递，不把整段收进内存。
 * 不跟随重定向：上游若返回 302，原样交回调用方，避免一次放行之后被带到名单外的主机。
 * 日志只记异常类型。
 */
public final class JdkUpstreamClient implements UpstreamClient, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JdkUpstreamClient.class);
    private final long timeoutMillis;
    private final HttpClient http;

    /**
     * 建立不跟随重定向的客户端。此时不拨号。
     *
     * @param timeoutMillis 等到响应头的超时毫秒数；正文按流继续读，不先收完整段
     */
    public JdkUpstreamClient(long timeoutMillis) {
        this.timeoutMillis = Math.max(1, timeoutMillis);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 把请求送到上游。HEAD 和空正文不带体；其余方法把调用方的流原样写出去。
     * 响应体留在返回的流里，由调用方按块读。连接失败时返回未到达，不附带异常原文。
     *
     * @param method 大写的 HTTP 方法
     * @param uri 规范绝对地址
     * @param headers 已过滤的请求头
     * @param body 原始正文流，可以是空
     * @param contentLength 正数是已知长度，0 表示没有正文，负数表示长度未知
     * @return 上游响应；失败时未到达。调用方负责关闭
     */
    @Override
    public UpstreamResponse exchange(String method, URI uri, Map<String, List<String>> headers, InputStream body, long contentLength) {
        if (method == null || method.isBlank() || uri == null) {
            return UpstreamResponse.failed();
        }
        InputStream payload = contentLength == 0 ? null : body;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .method(method, publisher(method, payload));
            if (headers != null) {
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    for (String value : entry.getValue()) {
                        builder.header(entry.getKey(), value);
                    }
                }
            }
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            String contentType = response.headers().firstValue("content-type").orElse("");
            return UpstreamResponse.reached(response.statusCode(), contentType, response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.info("upstream interrupted kind={}", ex.getClass().getSimpleName());
            return UpstreamResponse.failed();
        } catch (Exception ex) {
            log.info("upstream failed kind={}", ex.getClass().getSimpleName());
            return UpstreamResponse.failed();
        }
    }

    /**
     * 关闭底层客户端，释放连接。重复关闭没有额外作用。
     */
    @Override
    public void close() {
        http.close();
    }

    /**
     * 按方法选择正文发布器。HEAD 和空流不带正文；其余每读到一块就送出去，不先读完整段。
     *
     * @param method 大写方法
     * @param payload 调用方给的正文流，空表示没有正文
     * @return JDK 客户端使用的正文发布器
     */
    private static HttpRequest.BodyPublisher publisher(String method, InputStream payload) {
        String upper = method.toUpperCase(Locale.ROOT);
        if ("HEAD".equals(upper) || payload == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofInputStream(() -> payload);
    }
}
