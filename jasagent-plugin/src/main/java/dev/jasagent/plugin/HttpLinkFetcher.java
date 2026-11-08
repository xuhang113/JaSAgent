package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class HttpLinkFetcher implements LinkFetcher {
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
