package dev.jasagent.app;

import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.LocalAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExecutionFoundationTest {
    private static final Path home = create("jasagent-foundation-home");
    private static final Path workspace = create("jasagent-foundation-ws");
    private static final String PASSWORD = "test-password-1";
    private static final int BUDGET_TOKENS = 8000;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    LocalAuthService auth;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jasagent.home", () -> home.toString());
        registry.add("jasagent.workspaces", () -> workspace.toString());
        registry.add("server.address", () -> "127.0.0.1");
    }

    @Test
    void sseReachesSucceeded() throws Exception {
        writeCallRag();
        writeUserPolicy("sse-ok", """
                rules:
                - tool: rag.search
                  decision: ALLOW
                """);
        String cookie = login("sse-ok");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "call-rag", "input", "hello")));
        String body = sse(cookie, executionId);
        assertTrue(body.contains("event:status"), body);
        assertTrue(body.contains(executionId), body);
        assertTrue(body.contains("SUCCEEDED"), body);
    }

    @Test
    void sseReachesFailed() throws Exception {
        writeShowSupport();
        String cookie = login("sse-fail");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "show-support", "input", "现在几点")));
        String body = sse(cookie, executionId);
        assertTrue(body.contains("event:status"), body);
        assertTrue(body.contains(executionId), body);
        assertTrue(body.contains("FAILED"), body);
        assertFalse(body.contains("\"status\":\"SUCCEEDED\""), body);
    }

    @Test
    void sseReachesOffline() throws Exception {
        writeShowSupport();
        String cookie = login("sse-off");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "show-support", "input", "现在几点")));
        String body = sse(cookie, executionId);
        assertTrue(body.contains("event:status"), body);
        assertTrue(body.contains(executionId), body);
        assertTrue(body.contains("OFFLINE"), body);
        assertTrue(body.contains("FAILED"), body);
        assertFalse(body.contains("\"status\":\"SUCCEEDED\""), body);
    }

    @Test
    void httpDetailIsOfflineWhenModelMissing() throws Exception {
        writeShowSupport();
        String cookie = login("http-off");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "show-support", "input", "现在几点")));
        String detail = awaitBody(cookie, executionId, "\"status\":\"FAILED\"");
        assertTrue(detail.contains("\"errorCode\":\"OFFLINE\""), detail);
        assertFalse(detail.contains("\"status\":\"SUCCEEDED\""), detail);
    }

    @Test
    @Timeout(value = 20)
    void httpInterruptStopsRunningExecution() throws Exception {
        writeUserPolicy("stop-run", """
                rules:
                - tool: rag.link
                  decision: ALLOW
                """);
        writeWorkflow("hold-link", """
                {"id":"hold-link","nodes":[{"id":"fetch","type":"tool","tool":"rag.link"},{"id":"done","type":"end"}]}
                """);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int holdPort = server.getLocalPort();
            assertNotEquals(18721, holdPort);
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    Thread.sleep(60_000);
                } catch (Exception ignored) {
                    // 监听关掉后接受线程退出。
                }
            });
            try {
                String cookie = login("stop-run");
                selectWorkspace(cookie);
                String executionId = executionId(post("/api/executions", cookie, Map.of(
                        "workflowId", "hold-link",
                        "input", "{\"url\":\"http://127.0.0.1:" + holdPort + "/hold.txt\",\"scope\":\"workspace\"}")));
                String last = "";
                boolean running = false;
                for (int attempt = 0; attempt < 40; attempt++) {
                    last = get("/api/executions/" + executionId, cookie).getBody();
                    if (last != null && last.contains("\"status\":\"RUNNING\"")) {
                        running = true;
                        break;
                    }
                    if (last != null && (last.contains("\"status\":\"SUCCEEDED\"")
                            || last.contains("\"status\":\"FAILED\"")
                            || last.contains("\"status\":\"SUSPENDED\""))) {
                        break;
                    }
                    Thread.sleep(50);
                }
                assertTrue(running, "中止前执行没有处于 RUNNING。最后详情：" + last);
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/executions/" + executionId + "/interrupt"))
                        .timeout(Duration.ofSeconds(3))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
                HttpResponse<String> stopped = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                        .send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, stopped.statusCode(), stopped.body());
                assertTrue(stopped.body().contains("\"status\":\"SUSPENDED\""), stopped.body());
                assertTrue(stopped.body().contains("interrupt"), stopped.body());
                assertFalse(stopped.body().contains("\"status\":\"SUCCEEDED\""), stopped.body());
            } finally {
                acceptor.interrupt();
            }
        }
    }

    @Test
    @Timeout(value = 30)
    void sessionCallsWebSearchRagLinkAndGithubLink() throws Exception {
        writeUserPolicy("plugin-user", """
                rules:
                - tool: web.search
                  decision: ALLOW
                - tool: rag.link
                  decision: ALLOW
                - tool: github.link
                  decision: ALLOW
                """);
        writeWorkflow("web-search", """
                {"id":"web-search","nodes":[{"id":"search","type":"tool","tool":"web.search"},{"id":"done","type":"end"}]}
                """);
        writeWorkflow("rag-link", """
                {"id":"rag-link","nodes":[{"id":"link","type":"tool","tool":"rag.link"},{"id":"done","type":"end"}]}
                """);
        writeWorkflow("github-link", """
                {"id":"github-link","nodes":[{"id":"repo","type":"tool","tool":"github.link"},{"id":"done","type":"end"}]}
                """);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        assertNotEquals(18721, server.getAddress().getPort());
        server.createContext("/note.txt", exchange -> {
            byte[] payload = "hello from link\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try {
            String cookie = login("plugin-user");
            selectWorkspace(cookie);
            String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "插件工具")));
            assertSessionTool(cookie, sessionId, "web-search", "jasagent", "web.search", "local:jasagent");
            String link = "{\"url\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/note.txt\",\"scope\":\"workspace\"}";
            assertSessionTool(cookie, sessionId, "rag-link", link, "rag.link", "docId: note.txt");
            assertSessionTool(cookie, sessionId, "github-link", "acme/widgets", "github.link", "repository: acme/widgets");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void contextBelowThresholdDoesNotCompress() throws Exception {
        String cookie = login("ratio-low");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "低于阈值")));
        String body = get("/api/sessions/" + sessionId + "/context", cookie).getBody();
        assertNotNull(body);
        double occupancy = occupancy(body);
        assertTrue(occupancy < 0.85, "占用 " + occupancy + " 已经达到 0.85");
        assertTrue(occupancy >= 0.0, "占用 " + occupancy);
        assertTrue(emptySummary(body), "低于 0.85 时已发布摘要不是空的");
        assertTrue(body.replace(" ", "").contains("\"compressing\":false"), "低于 0.85 时仍在压缩");
    }

    @Test
    @Timeout(value = 60)
    void contextAtThresholdKeepsPublishedSummary() throws Exception {
        writeWorkflow("clock-only", """
                {"id":"clock-only","nodes":[{"id":"now","type":"tool","tool":"clock"},{"id":"done","type":"end"}]}
                """);
        String cookie = login("ratio-high");
        selectWorkspace(cookie);
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "达到阈值")));
        String older = "A".repeat(14000);
        String second = "B".repeat(14000);
        post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", older, "workflowId", "clock-only"));
        post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", second, "workflowId", "clock-only"));
        post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", "1", "workflowId", "clock-only"));
        post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", "2", "workflowId", "clock-only"));
        post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", "3", "workflowId", "clock-only"));
        post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", "4", "workflowId", "clock-only"));
        int chars = older.length() + second.length() + 4;
        double fixture = (chars / 4.0) / BUDGET_TOKENS;
        assertTrue(fixture >= 0.85 && fixture < 1.0, String.valueOf(fixture));
        String body = get("/api/sessions/" + sessionId + "/context", cookie).getBody();
        assertNotNull(body);
        double occupancy = occupancy(body);
        assertTrue(occupancy >= 0.85, "占用 " + occupancy + " 没有达到 0.85");
        assertTrue(emptySummary(body), "压缩未完成时已发布摘要被换掉了");
        assertFalse(body.contains("\"summary\":\"模型未配置\""), "失败文本被当成了已发布摘要");
        assertFalse(body.contains("PARTIAL"), "未完成摘要被发布了");
        assertTrue(body.replace(" ", "").contains("\"compressing\":false"), "压缩失败后仍处于压缩中");
    }

    private void assertSessionTool(String cookie, String sessionId, String workflowId, String text, String name, String marker) throws Exception {
        ResponseEntity<String> sent = post("/api/sessions/" + sessionId + "/messages", cookie, Map.of(
                "text", text,
                "workflowId", workflowId));
        assertEquals(HttpStatus.OK, sent.getStatusCode(), sent.getBody());
        String executionId = field(sent.getBody(), "executionId");
        String detail = awaitBody(cookie, executionId, "\"status\":\"SUCCEEDED\"");
        String stream = sse(cookie, executionId);
        String surface = detail + "\n" + stream;
        assertTrue(surface.contains(name), surface);
        assertTrue(surface.contains(marker), surface);
    }

    private String sse(String cookie, String executionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/executions/" + executionId + "/events"))
                .timeout(Duration.ofSeconds(20))
                .header("Cookie", cookie)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertNotNull(response.body());
        return response.body();
    }

    private String login(String user) throws Exception {
        ensureUser(user);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = http.exchange(
                "/login",
                HttpMethod.POST,
                new HttpEntity<>("user=" + user + "&password=" + PASSWORD, headers),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";", 2)[0];
    }

    private void selectWorkspace(String cookie) {
        ResponseEntity<String> response = post("/api/workspace", cookie, Map.of("path", workspace.toString()));
        assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
    }

    private ResponseEntity<String> post(String path, String cookie, Map<String, String> body) {
        return exchange(path, HttpMethod.POST, cookie, body);
    }

    private ResponseEntity<String> get(String path, String cookie) {
        return exchange(path, HttpMethod.GET, cookie, null);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String cookie, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private void ensureUser(String user) throws Exception {
        char[] secret = PASSWORD.toCharArray();
        try {
            auth.login(user, secret).toCompletableFuture().join();
            return;
        } catch (Exception ignored) {
            // 这个登录名还不存在。
        }
        try {
            auth.addUser(new AddUser(user, secret, null, false)).toCompletableFuture().join();
            return;
        } catch (Exception ignored) {
            // 已经有别的用户，需要已登录的本机会话。
        }
        try (var children = Files.list(home)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                try {
                    Session admin = auth.login(name, secret).toCompletableFuture().join();
                    auth.addUser(new AddUser(user, secret, admin.token(), true)).toCompletableFuture().join();
                    return;
                } catch (Exception ignored) {
                    // 试下一个已有用户。
                }
            }
        }
        throw new IllegalStateException("无法创建用户");
    }

    private String awaitBody(String cookie, String executionId, String marker) throws Exception {
        String body = "";
        for (int attempt = 0; attempt < 80; attempt++) {
            body = get("/api/executions/" + executionId, cookie).getBody();
            if (body != null && body.contains(marker)) {
                return body;
            }
            Thread.sleep(40);
        }
        assertTrue(body != null && body.contains(marker), body);
        return body;
    }

    private void writeCallRag() throws Exception {
        writeWorkflow("call-rag", """
                {"id":"call-rag","nodes":[{"id":"search","type":"tool","tool":"rag.search"},{"id":"done","type":"end"}]}
                """);
    }

    private void writeShowSupport() throws Exception {
        writeWorkflow("show-support", """
                {"id":"show-support","nodes":[{"id":"now","type":"tool","tool":"clock"},{"id":"act","type":"react","maxSteps":2,"tools":[],"grounding":false},{"id":"done","type":"end"}]}
                """);
    }

    private void writeWorkflow(String id, String json) throws Exception {
        Path dir = workspace.resolve("jasagent").resolve("workflows");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), json);
    }

    private void writeUserPolicy(String user, String yaml) throws Exception {
        Path file = home.resolve(user).resolve("jasagent").resolve("auth").resolve("policies.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    private static boolean emptySummary(String body) {
        return body.contains("\"summary\":\"\"") || body.contains("\"summary\":null");
    }

    private static double occupancy(String body) {
        String marker = "\"occupancy\":";
        int start = body.indexOf(marker);
        assertTrue(start >= 0, "上下文没有返回 occupancy");
        int from = start + marker.length();
        int end = from;
        while (end < body.length() && "0123456789.eE-+".indexOf(body.charAt(end)) >= 0) {
            end++;
        }
        assertTrue(end > from, "上下文的 occupancy 不是数字");
        return Double.parseDouble(body.substring(from, end));
    }

    private static String sessionId(ResponseEntity<String> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "sessionId");
    }

    private static String executionId(ResponseEntity<String> response) {
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "executionId");
    }

    private static String field(String body, String name) {
        String marker = "\"" + name + "\":\"";
        int start = body.indexOf(marker);
        assertTrue(start >= 0, body);
        start += marker.length();
        return body.substring(start, body.indexOf('"', start));
    }

    private static Path create(String name) {
        try {
            return Files.createTempDirectory(name);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
