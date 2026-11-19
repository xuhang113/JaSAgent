package dev.jasagent.llm.egress;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

/**
 * 大模型 HTTP 的传输。回环和本集群地址原样拨号；离开集群的地址改送到 sidecar {@code /v1/forward}。
 * 流式正文在调用线程上按块读出并立刻交给解析器，不先收完整段，也不使用会在 {@code synchronized} 里读流的
 * JDK 默认响应体处理器。调用方应是虚拟线程，读等待不会钉住载体。
 * 不跟随重定向，不记录密钥、授权头和正文。
 */
public final class SidecarLlmTransport implements HttpClient {
    /**
     * 没有给出连接超时时使用的缺省值。
     */
    private static final Duration DEFAULT_CONNECT = Duration.ofSeconds(10);
    /**
     * 没有给出读取超时时使用的缺省值。覆盖整次交换，避免无限等在上游。
     */
    private static final Duration DEFAULT_READ = Duration.ofSeconds(60);
    /**
     * 失败正文最多保留的字节。更长的部分丢掉，避免把整段错误页收进异常。
     */
    private static final int ERROR_LIMIT = 4096;

    private final java.net.http.HttpClient client;
    private final Duration readTimeout;

    /**
     * 建好只给大模型用的 HTTP/1.1 客户端。此时不拨号，也不跟随重定向。
     *
     * @param connectTimeout 连接超时；空或非正时按 10 秒
     * @param readTimeout 等到响应结束的时间；空或非正时按 60 秒
     */
    public SidecarLlmTransport(Duration connectTimeout, Duration readTimeout) {
        Duration connect = positive(connectTimeout) ? connectTimeout : DEFAULT_CONNECT;
        this.readTimeout = positive(readTimeout) ? readTimeout : DEFAULT_READ;
        this.client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(connect)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /**
     * 发送一次非流式请求。离开集群时先改写到 sidecar。成功响应的正文是调用方契约要求的完整文本，不是转发代理的流。
     *
     * @param request LangChain4j 准备好的请求
     * @return 上游成功响应
     */
    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        HttpResponse<InputStream> response = send(route(request));
        String text = readLimited(response.body(), Integer.MAX_VALUE);
        if (!successful(response.statusCode())) {
            throw new HttpException(response.statusCode(), text);
        }
        return SuccessfulHttpResponse.builder()
                .statusCode(response.statusCode())
                .headers(response.headers().map())
                .body(text)
                .build();
    }

    /**
     * 发送一次事件流请求，并在当前线程上把每块交给解析器。
     * 解析器在上游结束前就会收到已经到达的事件。读等待发生在队列上，不进入 {@code synchronized}。
     *
     * @param request LangChain4j 准备好的请求
     * @param parser 事件流解析器
     * @param listener 思考和正文增量的接收方
     */
    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("缺少事件监听");
        }
        try {
            HttpResponse<InputStream> response = send(route(request));
            if (!successful(response.statusCode())) {
                String text = readLimited(response.body(), ERROR_LIMIT);
                quietly(() -> listener.onError(new HttpException(response.statusCode(), text)));
                return;
            }
            SuccessfulHttpResponse open = SuccessfulHttpResponse.builder()
                    .statusCode(response.statusCode())
                    .headers(response.headers().map())
                    .build();
            quietly(() -> listener.onOpen(open));
            try (InputStream body = response.body()) {
                if (parser != null) {
                    parser.parse(body, listener);
                }
            }
            quietly(listener::onClose);
        } catch (RuntimeException ex) {
            quietly(() -> listener.onError(ex));
        } catch (IOException ex) {
            quietly(() -> listener.onError(new RuntimeException("出站失败")));
        }
    }

    /**
     * 直连时保持原请求。离开集群时把 URL 换成 sidecar 的转发入口，原地址放进专用头。
     *
     * @param request 原始请求
     * @return 可以拨号的请求
     */
    private static HttpRequest route(HttpRequest request) {
        if (request == null || EgressRoute.direct(request.url())) {
            return request;
        }
        HttpRequest.Builder builder = HttpRequest.builder()
                .method(request.method())
                .url(SidecarAddress.forwardUrl());
        if (request.headers() != null) {
            for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                builder.addHeader(entry.getKey(), entry.getValue().toArray(String[]::new));
            }
        }
        builder.addHeader(SidecarAddress.URL_HEADER, request.url());
        if (request.body() != null) {
            builder.body(request.body());
        }
        return builder.build();
    }

    /**
     * 异步发出请求，并在当前线程上等到响应头。正文仍是按块到达的流。
     *
     * @param request 已经改写过的请求
     * @return 带状态、响应头和正文流的响应
     */
    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return client.sendAsync(toJdk(request), CarrierFreeResponse.handler()).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("出站被中断");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof HttpTimeoutException timeout) {
                throw new TimeoutException(timeout);
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException("出站失败");
        }
    }

    /**
     * 把 LangChain4j 请求转成 JDK 请求。明文 HTTP 固定用 HTTP/1.1，避免 h2c 丢掉正文。不跟随重定向。
     *
     * @param request 要拨号的请求
     * @return JDK 客户端可以发送的请求
     */
    private java.net.http.HttpRequest toJdk(HttpRequest request) {
        if (request == null || request.url() == null) {
            throw new IllegalArgumentException("缺少请求地址");
        }
        boolean form = request.formDataFields() != null && !request.formDataFields().isEmpty()
                || request.formDataFiles() != null && !request.formDataFiles().isEmpty();
        if (form) {
            throw new IllegalArgumentException("不支持表单出站");
        }
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(URI.create(request.url()))
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .timeout(readTimeout);
        if (request.headers() != null) {
            request.headers().forEach((name, values) -> {
                if (name == null || name.isBlank() || values == null) {
                    return;
                }
                for (String value : values) {
                    if (value != null) {
                        builder.header(name, value);
                    }
                }
            });
        }
        java.net.http.HttpRequest.BodyPublisher publisher = request.body() == null
                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(request.body());
        String method = request.method() == null ? "POST" : request.method().name();
        builder.method(method, publisher);
        return builder.build();
    }

    /**
     * 把正文读成文本。流式成功路径不走这里。
     *
     * @param body 响应流，可以是 null
     * @param limit 最多保留的字节
     * @return UTF-8 文本；没有正文时为空串
     */
    private static String readLimited(InputStream body, int limit) {
        if (body == null) {
            return "";
        }
        try (InputStream input = body) {
            byte[] data = input.readAllBytes();
            if (data.length > limit) {
                byte[] cut = new byte[limit];
                System.arraycopy(data, 0, cut, 0, limit);
                data = cut;
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("出站失败");
        }
    }

    /**
     * 判断状态码是不是成功。
     *
     * @param status HTTP 状态码
     * @return 2xx 时为 true
     */
    private static boolean successful(int status) {
        return status >= 200 && status < 300;
    }

    /**
     * 判断超时是不是正数。
     *
     * @param duration 超时；可以是 null
     * @return 大于 0 时为 true
     */
    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    /**
     * 调用监听器并吞掉它自己抛出的异常，避免一个回调中断整段流的收尾。
     *
     * @param action 监听器回调
     */
    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 监听器的失败不再向外抛，也不记录可能含地址的消息。
        }
    }
}
