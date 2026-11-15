package dev.jasagent.sidecar;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 一次出站交给调用方的结果。拒绝时正文是本进程生成的短 JSON；转发时正文是上游的流。
 * 两种情况都不把上游正文收成完整数组。用完要关闭。
 */
public final class EgressExchange implements AutoCloseable {
    private final int status;
    private final String contentType;
    private final InputStream body;
    private final boolean forwarded;
    private final UpstreamResponse upstream;

    /**
     * 记住状态、正文流，以及要一起关掉的上游响应。
     *
     * @param status HTTP 状态码
     * @param contentType 响应内容类型
     * @param body 响应正文流，不会是空引用
     * @param forwarded 是否把上游响应交了回去
     * @param upstream 转发时的上游响应；拒绝时为空，关闭只关正文流
     */
    private EgressExchange(int status, String contentType, InputStream body, boolean forwarded, UpstreamResponse upstream) {
        this.status = status;
        this.contentType = contentType == null ? "" : contentType;
        this.body = body == null ? InputStream.nullInputStream() : body;
        this.forwarded = forwarded;
        this.upstream = upstream;
    }

    /**
     * 用固定原因码造一份拒绝结果。JSON 很短，由本进程生成，不含调用方正文。
     *
     * @param reason {@link EgressReasons} 里的原因码
     * @return 未转发的结果
     */
    public static EgressExchange rejected(String reason) {
        EgressOutcome outcome = EgressOutcome.rejected(reason);
        return new EgressExchange(outcome.status(), outcome.contentType(),
                new ByteArrayInputStream(outcome.body()), false, null);
    }

    /**
     * 把已经拿到的上游响应交出去。没有内容类型时用二进制流，避免调用方误当成拒绝 JSON。
     * 关闭返回值时会关掉上游响应。
     *
     * @param response 已到达的上游响应，不能为空
     * @return 已转发的结果
     */
    public static EgressExchange forwarded(UpstreamResponse response) {
        String type = response.contentType();
        if (type.isBlank()) {
            type = "application/octet-stream";
        }
        return new EgressExchange(response.status(), type, response.body(), true, response);
    }

    /**
     * HTTP 状态码。
     *
     * @return 写给调用方的状态码
     */
    public int status() {
        return status;
    }

    /**
     * 响应内容类型。
     *
     * @return 不会是空引用
     */
    public String contentType() {
        return contentType;
    }

    /**
     * 响应正文流。拒绝时是短 JSON；转发时是上游原文，按块读。
     *
     * @return 不会是空引用的输入流
     */
    public InputStream body() {
        return body;
    }

    /**
     * 这次是否把上游响应交了回去。
     *
     * @return 上游已返回时为真
     */
    public boolean forwarded() {
        return forwarded;
    }

    /**
     * 关闭正文和上游连接。重复关闭没有额外作用。
     */
    @Override
    public void close() {
        if (upstream != null) {
            upstream.close();
            return;
        }
        try {
            body.close();
        } catch (IOException ex) {
            // 拒绝结果的内存流关闭失败时不再向外抛。
        }
    }
}
