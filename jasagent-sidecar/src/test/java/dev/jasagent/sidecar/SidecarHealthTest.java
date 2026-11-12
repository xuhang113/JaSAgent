package dev.jasagent.sidecar;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动 sidecar 自己的随机端口，确认探针和免证名单已经装进 Spring 上下文。
 * 不连接 18741 上已有的通行证服务，也不去碰网关和 agent 的端口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "server.port=0",
        "jasagent.sidecar.agentpass-base-url=http://127.0.0.1:9",
        "jasagent.sidecar.agentpass-timeout-millis=200",
        "jasagent.sidecar.upstream-timeout-millis=200",
        "jasagent.sidecar.llm-allowlist=hooks.example.com"
})
class SidecarHealthTest {
    private static final Set<Integer> RESERVED = Set.of(18721, 18731, 18732, 18741, 18751, 8080);

    @LocalServerPort
    private int port;

    @Autowired
    private LlmAllowlist allowlist;

    /**
     * {@code GET /health} 返回 up。监听端口是随机的，不是约定的 8080，也不是已有进程的端口。
     */
    @Test
    void healthIsUpOnRandomPort() throws Exception {
        assertFalse(RESERVED.contains(port));
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"up\""));
        }
        assertTrue(allowlist.permits(LlmAllowlist.DEEPSEEK_HOST));
        assertTrue(allowlist.permits(LlmAllowlist.XAI_HOST));
        assertTrue(allowlist.permits("hooks.example.com"));
        assertFalse(allowlist.permits("example.com"));
    }
}
