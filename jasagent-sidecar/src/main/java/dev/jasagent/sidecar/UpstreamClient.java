package dev.jasagent.sidecar;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 把已经获准的请求送到上游。实现不得跟随重定向，也不得记录正文、授权头或通行证。
 */
public interface UpstreamClient {
    /**
     * 按给定方法和地址访问上游，并把允许外带的头和正文送出去。
     *
     * @param method 已经规范成大写的 HTTP 方法
     * @param uri 规范后的绝对地址
     * @param headers 已经去掉通行证和逐跳头的请求头
     * @param body 原始正文；GET 或 HEAD 可以为空数组
     * @return 上游响应；连接失败时 {@code reached} 为假
     */
    UpstreamResponse exchange(String method, URI uri, Map<String, List<String>> headers, byte[] body);
}
