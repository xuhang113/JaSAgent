package dev.jasagent.sidecar;

/**
 * 上游返回给 sidecar 的结果。连接失败时 {@code reached} 为假，调用方改写成 502，不把异常原文送回去。
 *
 * @param reached 是否已经从上游拿到了 HTTP 响应；重定向也算拿到，但不会继续跟过去
 * @param status 上游状态码；没连上时为 0
 * @param contentType 上游的 Content-Type，没有时为空串
 * @param body 上游正文，不会是空引用；sidecar 不记录它
 */
public record UpstreamResponse(boolean reached, int status, String contentType, byte[] body) {
    /**
     * 构造一份已经到达上游的响应。
     *
     * @param status 上游状态码
     * @param contentType 上游内容类型，可以是空
     * @param body 上游正文，空引用会收成空数组
     * @return 已到达的响应
     */
    public static UpstreamResponse reached(int status, String contentType, byte[] body) {
        return new UpstreamResponse(true, status, contentType == null ? "" : contentType,
                body == null ? new byte[0] : body);
    }

    /**
     * 构造一份没有到达上游的结果。
     *
     * @return 未到达，状态码为 0
     */
    public static UpstreamResponse failed() {
        return new UpstreamResponse(false, 0, "", new byte[0]);
    }
}
