package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 用 HTTP GET 抓取链接。
 * 连接和读取各等 5 秒，并跟随重定向；400 及以上的状态码当成工具错误。
 */
public final class HttpLinkFetcher implements LinkFetcher {
    /**
     * 发送 GET 并读回正文。
     * 副作用：访问该链接；线程被中断时重新置上中断标记，并以工具错误失败。没有内容类型时按 text/plain。
     *
     * @param url 要抓取的 http 或 https 链接
     * @return 响应的内容类型和正文
     */
    @Override
    public LinkResponse fetch(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new AgentException(ErrorCode.TOOL, "link status " + response.statusCode());
            }
            String type = response.headers().firstValue("content-type").orElse("text/plain");
            return new LinkResponse(type, response.body());
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
}
