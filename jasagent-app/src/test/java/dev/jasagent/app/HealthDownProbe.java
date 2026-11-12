package dev.jasagent.app;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 独立进程里的健康探针。用调用方给的家目录启动服务，请求 /health，并用一行 HEALTH_RESULT 报告状态码和正文。
 */
public final class HealthDownProbe {
    /**
     * 禁止实例化，探针只作为主程序运行。
     * 副作用：没有可复用的实例。
     */
    private HealthDownProbe() {
    }

    /**
     * 在 127.0.0.1 的随机端口启动应用。若端口仍是 18721 就以退出码 2 结束。否则请求 /health，存储确实为 down 且 HTTP 为 503 时退出码为 0。
     * 副作用：启动并结束 JVM。异常栈打到标准输出。
     *
     * @param args 第一项是 jasagent.home
     */
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
            if (port == null || "18721".equals(port) || "18731".equals(port) || "18732".equals(port)) {
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
