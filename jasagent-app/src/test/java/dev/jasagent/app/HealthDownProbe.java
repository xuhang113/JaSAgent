package dev.jasagent.app;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HealthDownProbe {
    private HealthDownProbe() {
    }

    public static void main(String[] args) {
        int status = 1;
        try {
            SpringApplication application = new SpringApplication(JasagentApplication.class);
            application.setDefaultProperties(java.util.Map.of("server.address", "127.0.0.1", "server.port", "0"));
            ConfigurableApplicationContext context = application.run(
                    "--server.address=127.0.0.1",
                    "--server.port=0",
                    "--jasagent.home=" + args[0]);
            String port = context.getEnvironment().getProperty("local.server.port");
            if (port == null || "18721".equals(port)) {
                System.out.println("HEALTH_RESULT refused " + port);
                System.exit(2);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            System.out.println("HEALTH_RESULT " + response.statusCode() + " " + body);
            boolean down = response.statusCode() == 503
                    && body.contains("\"status\":\"down\"")
                    && body.contains("\"storage\":\"down\"");
            status = down ? 0 : 1;
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
        }
        System.exit(status);
    }
}
