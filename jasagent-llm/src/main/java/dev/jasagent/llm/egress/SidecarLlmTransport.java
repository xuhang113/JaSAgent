package dev.jasagent.llm.egress;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.util.List;
import java.util.Map;

/**
 * 大模型 HTTP 的传输。回环和本集群地址原样交给 LangChain4j 的 JDK 客户端；
 * 离开集群的地址改送到 sidecar {@code /v1/forward}，响应仍按事件流交给调用方，不先收完整段。
 * 不记录密钥、授权头和正文。
 */
public final class SidecarLlmTransport implements HttpClient {
    private final HttpClient delegate;

    /**
     * 使用已经设好超时的底层客户端。直连和转发共用它，转发只改写请求地址。
     *
     * @param delegate LangChain4j 的 HTTP 客户端，负责真正拨号和事件流
     */
    public SidecarLlmTransport(HttpClient delegate) {
        this.delegate = delegate;
    }

    /**
     * 发送一次非流式请求。离开集群时先改写到 sidecar。
     *
     * @param request LangChain4j 准备好的请求
     * @return 上游成功响应
     */
    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        return delegate.execute(route(request));
    }

    /**
     * 发送一次事件流请求。离开集群时 sidecar 的响应按块交给解析器，不等整段结束。
     *
     * @param request LangChain4j 准备好的请求
     * @param parser 事件流解析器
     * @param listener 思考和正文增量的接收方
     */
    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        delegate.execute(route(request), parser, listener);
    }

    /**
     * 直连时保持原请求。离开集群时把 URL 换成 sidecar 的转发入口，原地址放进专用头。
     *
     * @param request 原始请求
     * @return 可以交给底层客户端的请求
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
}
