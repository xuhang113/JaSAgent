package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.llm.egress.SidecarForward;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 用 HTTP GET 抓取链接。
 * 离开集群的链接改送到本机回环上 sidecar 的 {@code /v1/forward}，正文按流读回；回环、Nacos、MySQL、Redis、网关和其它本集群地址仍直连。
 * 重定向按跳重新判断，每一跳离开集群仍走 sidecar，不会改由本进程直接拨公网。连接和读取各等 5 秒。400 及以上的状态码当成工具错误。
 * 口令、票据和响应正文不写进异常说明。
 */
public final class HttpLinkFetcher implements LinkFetcher {
    /**
     * 连接和单次读取的最长等待。直连和 sidecar 共用这一值。
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /**
     * 跟随重定向的次数上限，用来停住循环跳转。
     */
    private static final int REDIRECT_LIMIT = 5;

    /**
     * 这一次抓取使用的等待。生产构造器固定为 {@link #TIMEOUT}。
     */
    private final Duration timeout;

    /**
     * 使用 5 秒连接和读取等待。此时不拨号。
     */
    public HttpLinkFetcher() {
        this(TIMEOUT);
    }

    /**
     * 使用调用方给的等待。测试可以因此缩短连不上的集群地址要等的时间。
     *
     * @param timeout 连接和单次读取的最长等待；空引用时改用 5 秒
     */
    HttpLinkFetcher(Duration timeout) {
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? TIMEOUT : timeout;
    }

    /**
     * 发送 GET 并读回正文。
     * 副作用：访问该链接；离开集群时访问的是 sidecar。线程被中断时重新置上中断标记，并以工具错误失败。没有内容类型时按 text/plain。
     *
     * @param url 要抓取的 http 或 https 链接
     * @return 响应的内容类型和正文
     */
    @Override
    public LinkResponse fetch(String url) {
        try (SidecarForward client = SidecarForward.open(timeout, false)) {
            String current = url;
            for (int hop = 0; hop <= REDIRECT_LIMIT; hop++) {
                try (SidecarForward.Call response = client.exchange(
                        "GET", URI.create(current), Map.of(), new byte[0], timeout)) {
                    int status = response.status();
                    if (redirect(status)) {
                        if (hop == REDIRECT_LIMIT) {
                            throw new AgentException(ErrorCode.TOOL, "link status " + status);
                        }
                        current = nextUrl(URI.create(current), status, response.header("Location"));
                        continue;
                    }
                    String text = response.text();
                    if (response.rejected(text)) {
                        throw new AgentException(ErrorCode.TOOL, "link rejected");
                    }
                    if (status >= 400) {
                        throw new AgentException(ErrorCode.TOOL, "link status " + status);
                    }
                    String type = response.header("content-type");
                    if (type.isBlank()) {
                        type = "text/plain";
                    }
                    return new LinkResponse(type, text);
                }
            }
            throw new AgentException(ErrorCode.TOOL, "link redirected");
        } catch (AgentException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentException(ErrorCode.TOOL, "link interrupted", null, ex);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "link" : ex.getMessage();
            throw new AgentException(ErrorCode.TOOL, message, null, ex);
        }
    }

    /**
     * 判断这一跳是不是要继续跟随的重定向。只认 301、302、303、307 和 308。
     *
     * @param status HTTP 状态码
     * @return 应再发一次 GET 时为真
     */
    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * 把重定向位置解析成下一跳的绝对地址。只接受 http 和 https，并且不接受带用户信息的地址，避免口令进下一跳或异常。
     *
     * @param current 这一跳的地址
     * @param status 这一跳的状态码，位置不可用时写进说明
     * @param location 响应里的 Location，可以是相对路径
     * @return 下一跳的绝对地址
     */
    private static String nextUrl(URI current, int status, String location) {
        if (location == null || location.isBlank()) {
            throw new AgentException(ErrorCode.TOOL, "link status " + status);
        }
        URI resolved;
        try {
            resolved = current.resolve(location.trim());
        } catch (IllegalArgumentException ex) {
            throw new AgentException(ErrorCode.TOOL, "link status " + status, null, ex);
        }
        String scheme = resolved.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || resolved.getRawUserInfo() != null) {
            throw new AgentException(ErrorCode.TOOL, "link status " + status);
        }
        return resolved.toString();
    }
}
