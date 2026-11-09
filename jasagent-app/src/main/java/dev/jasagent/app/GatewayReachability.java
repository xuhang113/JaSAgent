package dev.jasagent.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class GatewayReachability implements SessionModelCatalog.GatewayProbe {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile String cachedUrl = "";
    private volatile boolean cachedReachable;
    private volatile long cachedAt;

    @Override
    public boolean reachable(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String url = baseUrl.trim();
        long now = System.currentTimeMillis();
        if (url.equals(cachedUrl) && now - cachedAt < 30_000) {
            return cachedReachable;
        }
        boolean open = probe(url);
        cachedUrl = url;
        cachedReachable = open;
        cachedAt = now;
        return open;
    }

    private boolean probe(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return false;
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            return false;
        }
    }
}
