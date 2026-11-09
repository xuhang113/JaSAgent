package dev.jasagent.app;

import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.llm.LlmSettings;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionModelCatalogTest {
    private static final String KEY = "placeholder-deepseek-key";

    @Test
    void deepSeekModelsComeFromTheEndpointAndDefaultStaysFirst() {
        SessionModelCatalog catalog = catalog(KEY, "", "", "", List.of("deepseek-v4-pro", "deepseek-flash"), true);
        List<SessionModelCatalog.ModelChoice> options = catalog.available();
        assertEquals(List.of("deepseek-flash", "deepseek-v4-pro"), options.stream().map(SessionModelCatalog.ModelChoice::id).toList());
        assertEquals(List.of("deepseek", "deepseek"), options.stream().map(SessionModelCatalog.ModelChoice::provider).toList());
        assertEquals("deepseek-v4-pro", catalog.require("deepseek-v4-pro", "deepseek").id());
    }

    @Test
    void missingDeepSeekKeyDoesNotListModels() {
        AtomicInteger calls = new AtomicInteger();
        SessionModelCatalog catalog = new SessionModelCatalog(
                settings("", "", "", ""),
                key -> {
                    calls.incrementAndGet();
                    return List.of("deepseek-flash");
                },
                url -> true);
        assertTrue(catalog.available().isEmpty());
        assertEquals(0, calls.get());
        AgentException error = assertThrows(AgentException.class, () -> catalog.require("deepseek-flash", "deepseek"));
        assertEquals(ErrorCode.WORKFLOW, error.code());
    }

    @Test
    void unconfiguredOrUnreachableGatewayIsHidden() {
        SessionModelCatalog missing = catalog(KEY, "https://gateway.example", "", "vendor-model", List.of("deepseek-flash"), true);
        assertFalse(ids(missing).contains("vendor-model"));
        SessionModelCatalog down = catalog(KEY, "https://gateway.example", "placeholder-gateway-key", "vendor-model", List.of("deepseek-flash"), false);
        assertFalse(ids(down).contains("vendor-model"));
        assertEquals("deepseek-flash", down.require("deepseek-flash", null).id());
        AgentException error = assertThrows(AgentException.class, () -> down.require("vendor-model", "ai-gateway"));
        assertEquals(ErrorCode.WORKFLOW, error.code());
    }

    @Test
    void reachableGatewayIsListed() {
        SessionModelCatalog catalog = catalog(
                KEY,
                "https://gateway.example",
                "placeholder-gateway-key",
                "vendor-model",
                List.of("deepseek-flash"),
                true);
        SessionModelCatalog.ModelChoice gateway = catalog.require("vendor-model", "ai-gateway");
        assertEquals("ai-gateway", gateway.provider());
        assertEquals("vendor-model", gateway.label());
    }

    @Test
    void collidingGatewayNameIsLabeled() {
        SessionModelCatalog catalog = catalog(
                KEY,
                "https://gateway.example",
                "placeholder-gateway-key",
                "deepseek-flash",
                List.of("deepseek-flash"),
                true);
        assertEquals("deepseek-flash（Gateway）", catalog.require("deepseek-flash", "ai-gateway").label());
        assertEquals("deepseek", catalog.require("deepseek-flash", "deepseek").provider());
    }

    @Test
    void directoryReadsModelIdsFromDeepSeek() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"data":[{"id":"deepseek-flash"},{"id":" deepseek-v4-pro "}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            DeepSeekModelDirectory directory = new DeepSeekModelDirectory(java.net.URI.create("http://127.0.0.1:" + port + "/models"));
            assertEquals(List.of(), directory.ids("  "));
            List<String> ids = directory.ids(KEY);
            assertEquals(List.of("deepseek-flash", "deepseek-v4-pro"), ids);
            assertTrue(authorization.get().startsWith("Bearer "));
            assertFalse(String.join(",", ids).contains(KEY));
            assertEquals("https://api.deepseek.com/models", DeepSeekModelDirectory.MODELS.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gatewayProbeFollowsAnAnswerAndRejectsAClosedPort() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        try {
            GatewayReachability probe = new GatewayReachability();
            assertTrue(probe.reachable("http://127.0.0.1:" + server.getAddress().getPort()));
            assertFalse(probe.reachable("http://127.0.0.1:" + closed));
            assertFalse(probe.reachable(""));
            assertFalse(probe.reachable("not a url"));
        } finally {
            server.stop(0);
        }
    }

    private static List<String> ids(SessionModelCatalog catalog) {
        return catalog.available().stream().map(SessionModelCatalog.ModelChoice::id).toList();
    }

    private static SessionModelCatalog catalog(
            String deepseekKey,
            String gatewayBase,
            String gatewayKey,
            String gatewayModel,
            List<String> deepseekIds,
            boolean gatewayUp) {
        return new SessionModelCatalog(settings(deepseekKey, gatewayBase, gatewayKey, gatewayModel), key -> deepseekIds, url -> gatewayUp);
    }

    private static LlmSettings settings(String deepseekKey, String gatewayBase, String gatewayKey, String gatewayModel) {
        return new LlmSettings(deepseekKey, false, gatewayBase, gatewayKey, gatewayModel, false, "", Duration.ofSeconds(60));
    }
}
