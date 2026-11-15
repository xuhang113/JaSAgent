package dev.jasagent.sidecar;

import java.io.IOException;
import java.io.InputStream;

/**
 * 上游返回给 sidecar 的结果。正文是流，调用方按块读走，本类不把整段收进内存。
 * 连接失败时 {@code reached} 为假。调用方用完要关闭，好放开到底层连接。
 */
public final class UpstreamResponse implements AutoCloseable {
    private final boolean reached;
    private final int status;
    private final String contentType;
    private final InputStream body;

    /**
     * 收成一份可关闭的上游结果。空正文用空流，避免调用方再判断空引用。
     *
     * @param reached 是否已经从上游拿到 HTTP 响应；重定向也算拿到
     * @param status 上游状态码；没连上时为 0
     * @param contentType 上游的 Content-Type，没有时为空串
     * @param body 上游正文流，空引用会收成空流
     */
    private UpstreamResponse(boolean reached, int status, String contentType, InputStream body) {
        this.reached = reached;
        this.status = status;
        this.contentType = contentType == null ? "" : contentType;
        this.body = body == null ? InputStream.nullInputStream() : body;
    }

    /**
     * 构造一份已经到达上游的响应。调用方负责关闭返回值。
     *
     * @param status 上游状态码
     * @param contentType 上游内容类型，可以是空
     * @param body 上游正文流，空引用会收成空流
     * @return 已到达的响应
     */
    public static UpstreamResponse reached(int status, String contentType, InputStream body) {
        return new UpstreamResponse(true, status, contentType, body);
    }

    /**
     * 构造一份没有到达上游的结果。
     *
     * @return 未到达，状态码为 0
     */
    public static UpstreamResponse failed() {
        return new UpstreamResponse(false, 0, "", InputStream.nullInputStream());
    }

    /**
     * 是否已经从上游拿到 HTTP 响应。
     *
     * @return 拿到状态行时为真
     */
    public boolean reached() {
        return reached;
    }

    /**
     * 上游状态码。
     *
     * @return 没连上时为 0
     */
    public int status() {
        return status;
    }

    /**
     * 上游内容类型。
     *
     * @return 没有时为空串
     */
    public String contentType() {
        return contentType;
    }

    /**
     * 上游正文流。按块读取，不要先收成完整数组。
     *
     * @return 不会是空引用的输入流
     */
    public InputStream body() {
        return body;
    }

    /**
     * 关闭正文流，放开到底层连接。重复关闭没有额外作用。
     */
    @Override
    public void close() {
        try {
            body.close();
        } catch (IOException ex) {
            // 关闭失败不再向外抛，避免把可能含地址的异常原文送回调用方。
        }
    }
}
