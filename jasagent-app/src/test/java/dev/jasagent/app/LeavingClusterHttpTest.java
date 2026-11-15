package dev.jasagent.app;

import com.sun.net.httpserver.HttpServer;
import dev.jasagent.llm.egress.EgressRoute;
import dev.jasagent.llm.egress.SidecarAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * agent 进程离开集群的 HTTP 打到 sidecar，而不是自己去拨公网地址。
 * Nacos 注册地址的回环检查保持原样。
 */
class LeavingClusterHttpTest {
    /**
     * 卸掉测试用的 sidecar 地址。
     */
    @AfterEach
    void clearSidecar() {
        SidecarAddress.clearTest();
    }

    /**
     * DeepSeek 的模型列表经 {@code /v1/forward} 发出。请求头里的密钥不出现在断言说明里。
     */
    @Test
    void modelDirectoryUsesForwardForDeepSeek() throws Exception {
        AtomicReference<String> target = new AtomicReference<>("");
        AtomicReference<String> path = new AtomicReference<>("");
        AtomicReference<Boolean> bearer = new AtomicReference<>(false);
        HttpServer sidecar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sidecar.createContext(SidecarAddress.FORWARD_PATH, exchange -> {
            path.set(exchange.getRequestURI().getPath());
            target.set(exchange.getRequestHeaders().getFirst(SidecarAddress.URL_HEADER));
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            bearer.set(authorization != null && authorization.startsWith("Bearer "));
            byte[] body = "{\"data\":[{\"id\":\"deepseek-flash\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        sidecar.start();
        try {
            SidecarAddress.useForTest("http://127.0.0.1:" + sidecar.getAddress().getPort());
            DeepSeekModelDirectory directory = new DeepSeekModelDirectory();
            List<String> ids = directory.ids("directory-key");
            assertEquals(List.of("deepseek-flash"), ids);
            assertEquals(SidecarAddress.FORWARD_PATH, path.get());
            assertEquals("https://api.deepseek.com/models", target.get());
            assertTrue(bearer.get());
            assertFalse(String.join(",", ids).contains("directory-key"));
        } finally {
            sidecar.stop(0);
        }
    }

    /**
     * 注册地址仍然拒绝回环。回环只留给同机 sidecar，不写进 Nacos。
     */
    @Test
    void registrationAddressStaysNonLoopback() {
        assertFalse(dev.jasagent.app.nacos.ReachableAddress.usable("127.0.0.1"));
        assertFalse(dev.jasagent.app.nacos.ReachableAddress.usable("localhost"));
        assertFalse(dev.jasagent.app.nacos.ReachableAddress.loopbackBind("0.0.0.0"));
        assertTrue(dev.jasagent.app.nacos.ReachableAddress.loopbackBind("127.0.0.1"));
        assertEquals("sidecar", EgressRoute.mode("https://mcp.example.com/rpc"));
        assertEquals("direct", EgressRoute.mode("http://jasagent-nacos:8848/nacos"));
    }
}
