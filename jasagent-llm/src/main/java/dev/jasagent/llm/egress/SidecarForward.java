package dev.jasagent.llm.egress;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 代理进程里的 HTTP。离开集群的请求改送到 sidecar 的 {@code /v1/forward}，正文和响应都按流传递。
 * 本集群和回环地址仍直连，不经过 sidecar。日志和异常不包含证明文、授权头或口令。
 */
public final class SidecarForward implements AutoCloseable {
    private final HttpClient direct;
    private final HttpClient sidecar;
    private final boolean followDirectRedirects;

    /**
     * 建好直连和转发两路客户端。此时不拨号。
     *
     * @param connectTimeout 连接超时
     * @param followDirectRedirects 直连时是否跟随重定向；走 sidecar 时始终不跟随
     */
    private SidecarForward(Duration connectTimeout, boolean followDirectRedirects) {
        this.followDirectRedirects = followDirectRedirects;
        this.direct = client(connectTimeout, followDirectRedirects);
        this.sidecar = client(connectTimeout, false);
    }

    /**
     * 打开一路出站客户端。
     *
     * @param connectTimeout 连接超时，空或非正时按 2 秒
     * @param followDirectRedirects 直连时是否跟随重定向
     * @return 还没拨号的客户端
     */
    public static SidecarForward open(Duration connectTimeout, boolean followDirectRedirects) {
        return new SidecarForward(connectTimeout, followDirectRedirects);
    }

    /**
     * 发送一次请求。离开集群时改打 sidecar，并把原地址放进 {@code X-Jasagent-Url}。
     * 响应体是流，调用方用完要关闭返回值。
     *
     * @param method HTTP 方法
     * @param uri 真正要访问的地址
     * @param headers 要带上的头，可以是空。走 sidecar 时原样交给它，由它决定哪些能到上游
     * @param body 正文；没有时为空数组
     * @param timeout 这次请求等到响应头的时间
     * @return 状态和正文流
     * @throws IOException 连接失败时抛出，消息不含正文和密钥
     * @throws InterruptedException 等待被中断时抛出，并恢复中断标记
     */
    public Call exchange(String method, URI uri, Map<String, List<String>> headers, byte[] body, Duration timeout)
            throws IOException, InterruptedException {
        boolean viaSidecar = uri != null && !EgressRoute.direct(uri.toString());
        byte[] payload = body == null ? new byte[0] : body;
        URI target = viaSidecar ? URI.create(SidecarAddress.forwardUrl()) : uri;
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(timeout == null ? Duration.ofSeconds(5) : timeout);
        if (viaSidecar) {
            builder.header(SidecarAddress.URL_HEADER, uri.toString());
        }
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    continue;
                }
                for (String value : entry.getValue()) {
                    if (value != null) {
                        builder.header(entry.getKey(), value);
                    }
                }
            }
        }
        builder.method(method, publisher(payload));
        try {
            HttpResponse<InputStream> response = (viaSidecar ? sidecar : direct)
                    .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new Call(response.statusCode(), response.body(), viaSidecar);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } catch (IOException ex) {
            throw new IOException("出站失败");
        }
    }

    /**
     * 关闭两路客户端。重复关闭没有额外作用。
     */
    @Override
    public void close() {
        direct.close();
        sidecar.close();
    }

    /**
     * 直连时是否跟随重定向。测试用来确认 sidecar 那一路没有打开跟随。
     *
     * @return 构造时传入的开关
     */
    public boolean followDirectRedirects() {
        return followDirectRedirects;
    }

    /**
     * 按连接超时和是否跟随重定向建一个 HTTP/1.1 客户端。
     *
     * @param connectTimeout 连接超时
     * @param follow 为真时跟随重定向
     * @return 还没拨号的客户端
     */
    private static HttpClient client(Duration connectTimeout, boolean follow) {
        Duration timeout = connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                ? Duration.ofSeconds(2) : connectTimeout;
        return HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(follow ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 空正文不带体；有内容时按流写出去，不再复制成另一份数组发布器。
     *
     * @param payload 调用方已经持有的正文，不会是空引用
     * @return JDK 客户端使用的发布器
     */
    private static HttpRequest.BodyPublisher publisher(byte[] payload) {
        if (payload.length == 0) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload));
    }

    /**
     * 一次出站的状态和正文流。走了 sidecar 时，短 JSON 里的 {@code admitted:false} 表示没有到达上游。
     */
    public static final class Call implements AutoCloseable {
        private final int status;
        private final InputStream body;
        private final boolean viaSidecar;

        /**
         * 记住状态、正文流，以及这次有没有经过 sidecar。
         *
         * @param status HTTP 状态码
         * @param body 响应流，不会是空引用
         * @param viaSidecar 请求发往 sidecar 时为真
         */
        private Call(int status, InputStream body, boolean viaSidecar) {
            this.status = status;
            this.body = body == null ? InputStream.nullInputStream() : body;
            this.viaSidecar = viaSidecar;
        }

        /**
         * HTTP 状态码。
         *
         * @return 上游或 sidecar 的状态码
         */
        public int status() {
            return status;
        }

        /**
         * 响应正文流。
         *
         * @return 不会是空引用
         */
        public InputStream body() {
            return body;
        }

        /**
         * 这次是否把请求交给了 sidecar。
         *
         * @return 离开集群时为真
         */
        public boolean viaSidecar() {
            return viaSidecar;
        }

        /**
         * sidecar 拒绝了这次转发，上游没有收到请求。只认固定的拒绝 JSON，不把正文写进日志。
         *
         * @param text 已经读出的正文
         * @return 经过 sidecar 且正文表示未放行时为真
         */
        public boolean rejected(String text) {
            return viaSidecar && text != null && text.contains("\"admitted\":false");
        }

        /**
         * 把正文读成文本。给模型目录、检索和领证这种短响应用；流式模型不走这里。
         *
         * @return UTF-8 文本；没有正文时为空串
         * @throws IOException 读取失败时抛出，不含正文
         */
        public String text() throws IOException {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }

        /**
         * 关闭正文流。重复关闭没有额外作用。
         */
        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ex) {
                // 关闭失败不再向外抛。
            }
        }
    }
}
