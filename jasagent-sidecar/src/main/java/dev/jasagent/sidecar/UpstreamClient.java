package dev.jasagent.sidecar;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 把已经获准的请求送到上游。实现按流读写正文，不得把整段收进内存，也不得跟随重定向，
 * 更不得记录正文、授权头或通行证。
 */
public interface UpstreamClient {
    /**
     * 按给定方法和地址访问上游，并把允许外带的头和正文流送出去。
     * 正文流由本方法在发送过程中读完；长度为 0 或流为空时不带正文。
     *
     * @param method 已经规范成大写的 HTTP 方法
     * @param uri 规范后的绝对地址
     * @param headers 已经去掉通行证和逐跳头的请求头
     * @param body 原始正文流；GET、HEAD 或空正文时为 null
     * @param contentLength 正数是已知长度，0 表示没有正文，负数表示长度未知、按块发送
     * @return 上游响应；连接失败时 {@code reached} 为假。调用方负责关闭
     */
    UpstreamResponse exchange(String method, URI uri, Map<String, List<String>> headers, InputStream body, long contentLength);
}
