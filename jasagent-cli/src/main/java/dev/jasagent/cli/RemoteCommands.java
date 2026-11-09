package dev.jasagent.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 命令行访问本机 HTTP 接口的客户端，也负责从家目录读出已保存的会话令牌。
 */
public final class RemoteCommands {
    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * 发送一次 GET 或 POST。令牌非空时带上 Bearer。POST 使用 JSON，正文为空时改发空对象。超时 30 秒。
     *
     * @param method POST 或其他；其他都按 GET 发送
     * @param url 完整地址
     * @param token 会话令牌，空白时不带头
     * @param body POST 正文，null 时发送空对象
     * @return 状态码、换行和响应正文拼成的文本
     */
    public String call(String method, String url, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        } else {
            builder.GET();
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode() + "\n" + response.body();
    }

    /**
     * 从家目录的 .jasagent/cli-session 读取 token= 开头的那一行。文件不存在或没有这一行时返回空串。
     *
     * @param home 数据根目录
     * @return 去掉空白后的令牌，没有时是空串
     */
    public static String token(Path home) throws Exception {
        Path file = home.resolve(".jasagent").resolve("cli-session");
        if (!Files.isRegularFile(file)) {
            return "";
        }
        for (String line : Files.readString(file).split("\n")) {
            if (line.startsWith("token=")) {
                return line.substring("token=".length()).trim();
            }
        }
        return "";
    }
}
