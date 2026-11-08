package dev.jasagent.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class RemoteCommands {
    private final HttpClient http = HttpClient.newHttpClient();

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
