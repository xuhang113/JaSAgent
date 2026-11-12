package dev.jasagent.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用 JDK HTTP 客户端访问已经获准的上游。不跟随重定向：上游若返回 302，原样交回调用方，
 * 避免一次放行之后被带到名单外或另一张证才能访问的主机。日志只记异常类型。
 */
public final class JdkUpstreamClient implements UpstreamClient, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JdkUpstreamClient.class);
    private final long timeoutMillis;
    private final HttpClient http;

    /**
     * 建立不跟随重定向的客户端。此时不拨号。
     *
     * @param timeoutMillis 连接和读取的超时毫秒数
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
     * 把请求送到上游。HEAD 不带正文；其它方法在正文为空时也不强行带上空正文。
     *
     * @param method 大写的 HTTP 方法
     * @param uri 规范绝对地址
     * @param headers 已过滤的请求头
     * @param body 原始正文
     * @return 上游响应；失败时未到达
     */
    @Override
    public UpstreamResponse exchange(String method, URI uri, Map<String, List<String>> headers, byte[] body) {
        if (method == null || method.isBlank() || uri == null) {
            return UpstreamResponse.failed();
        }
        byte[] payload = body == null ? new byte[0] : body;
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
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
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
     * 按方法选择正文发布器。HEAD 不能带正文；空正文的 GET 和 DELETE 用无正文，避免部分上游拒绝。
     *
     * @param method 大写方法
     * @param payload 调用方给的正文，不会是空引用
     * @return JDK 客户端使用的正文发布器
     */
    private static HttpRequest.BodyPublisher publisher(String method, byte[] payload) {
        String upper = method.toUpperCase(Locale.ROOT);
        if ("HEAD".equals(upper) || payload.length == 0) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofByteArray(payload);
    }
}
