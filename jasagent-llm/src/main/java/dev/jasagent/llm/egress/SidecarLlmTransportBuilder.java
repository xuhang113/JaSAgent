package dev.jasagent.llm.egress;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;

import java.time.Duration;

/**
 * 给 LangChain4j 的模型装上 sidecar 传输。超时仍由模型配置写入，聊天协议仍由 LangChain4j 生成。
 */
public final class SidecarLlmTransportBuilder implements HttpClientBuilder {
    private Duration connectTimeout;
    private Duration readTimeout;

    /**
     * 当前连接超时。
     *
     * @return 还没设置时为空
     */
    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /**
     * 记下连接超时，交给底层 JDK 客户端。
     *
     * @param connectTimeout 连接超时
     * @return 这个构造器
     */
    @Override
    public HttpClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    /**
     * 当前读取超时。
     *
     * @return 还没设置时为空
     */
    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    /**
     * 记下读取超时。事件流在这段时间内等到响应头，正文按块继续读。
     *
     * @param readTimeout 读取超时
     * @return 这个构造器
     */
    @Override
    public HttpClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    /**
     * 建出带 sidecar 改写的客户端。底层仍用 LangChain4j 找到的 JDK 客户端来拨号和读事件流。此时不拨号。
     *
     * @return 大模型请求使用的传输
     */
    @Override
    public HttpClient build() {
        HttpClientBuilder builder = HttpClientBuilderLoader.loadHttpClientBuilder();
        if (connectTimeout != null) {
            builder.connectTimeout(connectTimeout);
        }
        if (readTimeout != null) {
            builder.readTimeout(readTimeout);
        }
        return new SidecarLlmTransport(builder.build());
    }
}
