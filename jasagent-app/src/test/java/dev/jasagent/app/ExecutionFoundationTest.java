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

/**
 * 从 HTTP 和 SSE 核对执行能到成功、失败或离线，中止能打断正在跑的链接，插件工具能被会话叫到，上下文跨过阈值时也不发布半截摘要。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExecutionFoundationTest {
    /**
     * 本类共用的临时家目录。类加载时创建。
     */
    private static final Path home = create("jasagent-foundation-home");
    /**
     * 本类共用的临时工作区。类加载时创建，工作流和链接测试都写在这里。
     */
    private static final Path workspace = create("jasagent-foundation-ws");
    /**
     * 本类测试用户共用的口令，只用于夹具。
     */
    private static final String PASSWORD = "test-password-1";
    /**
     * 估算大消息占用时使用的上下文预算，用来判断夹具有没有跨过 0.85。
     */
    private static final int BUDGET_TOKENS = 8000;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    LocalAuthService auth;

    /**
     * 把家目录、工作区白名单和 127.0.0.1 写进测试上下文。
     * 副作用：后续请求都使用这套临时目录。
     *
     * @param registry 测试上下文的属性注册器
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jasagent.home", () -> home.toString());
        registry.add("jasagent.workspaces", () -> workspace.toString());
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("jasagent.internal.token", () -> "test-internal-token");
        registry.add("jasagent.egress.enabled", () -> "false");
    }

    /**
     * 允许 rag.search 后提交 call-rag。事件流里要有 status、执行编号和 SUCCEEDED。
     * 副作用：写工作流和用户策略，并打开 SSE。断言失败则测试失败。
     */
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

    /**
     * 没有模型时提交含 react 的 show-support。事件流含 FAILED 和执行编号，不能是成功状态。
     * 副作用：写工作流并打开 SSE。断言失败则测试失败。
     */
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

    /**
     * 同一条缺模型的 show-support，事件流必须同时出现 OFFLINE 和 FAILED，不能是成功。
     * 副作用：写工作流并打开 SSE。断言失败则测试失败。
     */
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

    /**
     * 缺模型时，执行详情最终是 FAILED，错误码是 OFFLINE，不能是成功。
     * 副作用：写工作流并轮询详情。断言失败则测试失败。
     */
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

    /**
     * rag.link 连到一个接受后不回应的本机端口。确认执行已是 RUNNING 后，3 秒内的中止必须返回 200 和 SUSPENDED，不能是成功。
     * 副作用：占用本机端口、写工作流和策略，并发送中止请求。断言失败则测试失败。
     */
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
            assertNotEquals(18731, holdPort);
            assertNotEquals(18732, holdPort);
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
                        .header("X-Jasagent-Token", "test-internal-token")
                        .header("X-Jasagent-Tenant", "local")
                        .header("X-Jasagent-User", cookie)
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

    /**
     * 在同一会话里依次调用 web.search、rag.link 和 github.link。详情或事件流要出现对应工具名，以及 local:jasagent、docId: note.txt、repository: acme/widgets。
     * 副作用：写三条工作流、用户策略，并启动一个返回 note.txt 的本机服务。断言失败则测试失败。
     */
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
        assertNotEquals(18731, server.getAddress().getPort());
        assertNotEquals(18732, server.getAddress().getPort());
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

    /**
     * 空会话的占用低于 0.85，已发布摘要是空的，compressing 为 false。
     * 副作用：创建用户和会话并读取上下文。断言失败则测试失败。
     */
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

    /**
     * 用大消息把估算占用推过 0.85 但不到 1。压缩没真正完成时，已发布摘要仍是空的，不能变成“模型未配置”或 PARTIAL，compressing 也是 false。
     * 副作用：写 clock-only 工作流并追加多条消息。断言失败则测试失败。
     */
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

    /**
     * 往会话发一条消息，等到执行成功，并要求详情或事件流同时含有工具名和结果标记。
     * 副作用：提交执行并读取 SSE。标记缺失时断言失败。
     *
     * @param cookie 登录 Cookie
     * @param sessionId 会话编号
     * @param workflowId 工作流编号
     * @param text 发给工作流的文本或 JSON
     * @param name 期望出现的工具名
     * @param marker 期望出现的结果片段
     */
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

    /**
     * 用 Cookie 订阅这次执行的事件流，要求状态码 200。
     *
     * @param cookie 登录 Cookie
     * @param executionId 执行编号
     * @return 事件流全文
     */
    private String sse(String cookie, String executionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/executions/" + executionId + "/events"))
                .timeout(Duration.ofSeconds(20))
                .header("X-Jasagent-Token", "test-internal-token")
                .header("X-Jasagent-Tenant", "local")
                .header("X-Jasagent-User", cookie)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertNotNull(response.body());
        return response.body();
    }

    /**
     * 确保用户存在后用本类口令登录，返回去掉属性后的会话 Cookie。
     * 副作用：用户不存在时会建号。
     *
     * @param user 登录名
     * @return Cookie 名值对
     */
    private String login(String user) throws Exception {
        ensureUser(user);
        return user;
    }

    /**
     * 替该会话选中本类工作区，响应必须成功。
     * 副作用：会话绑定到这个工作区。
     *
     * @param cookie 登录 Cookie
     */
    private void selectWorkspace(String cookie) {
        ResponseEntity<String> response = post("/api/workspace", cookie, Map.of("path", workspace.toString()));
        assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
    }

    /**
     * 带 Cookie 发送 JSON POST。
     *
     * @param path 接口路径
     * @param cookie 登录 Cookie
     * @param body JSON 字段
     * @return 响应
     */
    private ResponseEntity<String> post(String path, String cookie, Map<String, String> body) {
        return exchange(path, HttpMethod.POST, cookie, body);
    }

    /**
     * 带 Cookie 发送 GET。
     *
     * @param path 接口路径
     * @param cookie 登录 Cookie
     * @return 响应
     */
    private ResponseEntity<String> get(String path, String cookie) {
        return exchange(path, HttpMethod.GET, cookie, null);
    }

    /**
     * 带 Cookie 调用接口。有正文时声明 JSON。
     *
     * @param path 接口路径
     * @param method HTTP 方法
     * @param cookie 登录 Cookie
     * @param body 请求字段，null 表示没有正文
     * @return 响应
     */
    private ResponseEntity<String> exchange(String path, HttpMethod method, String cookie, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Jasagent-Token", "test-internal-token");
        headers.set("X-Jasagent-Tenant", "local");
        headers.set("X-Jasagent-User", cookie);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /**
     * 保证登录名可用。能登录就返回；否则建用户；已有用户时借用其中的管理员令牌。
     * 副作用：可能在家目录新增用户。
     *
     * @param user 登录名
     */
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

    /**
     * 最多约 3 秒轮询执行详情，直到正文含有标记。
     *
     * @param cookie 登录 Cookie
     * @param executionId 执行编号
     * @param marker 详情里必须出现的片段
     * @return 含标记的详情正文
     */
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

    /**
     * 在共用工作区写入 call-rag，节点是 rag.search 然后结束。
     * 副作用：覆盖 workflows/call-rag.json。
     */
    private void writeCallRag() throws Exception {
        writeWorkflow("call-rag", """
                {"id":"call-rag","nodes":[{"id":"search","type":"tool","tool":"rag.search"},{"id":"done","type":"end"}]}
                """);
    }

    /**
     * 在共用工作区写入 show-support：先 clock，再一个没有工具的 react，然后结束。
     * 副作用：覆盖 workflows/show-support.json。
     */
    private void writeShowSupport() throws Exception {
        writeWorkflow("show-support", """
                {"id":"show-support","nodes":[{"id":"now","type":"tool","tool":"clock"},{"id":"act","type":"react","maxSteps":2,"tools":[],"grounding":false},{"id":"done","type":"end"}]}
                """);
    }

    /**
     * 把工作流原文写到共用工作区，文件名是编号加 .json。不改 JSON 正文。
     * 副作用：创建 workflows 目录并覆盖文件。
     *
     * @param id 工作流编号
     * @param json 工作流原文
     */
    private void writeWorkflow(String id, String json) throws Exception {
        Path dir = workspace.resolve("jasagent").resolve("workflows");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), json);
    }

    /**
     * 把通行证策略写到该用户家目录的 policies.yaml。YAML 原文保持不动。
     * 副作用：创建 auth 目录并覆盖策略文件。
     *
     * @param user 登录名
     * @param yaml 策略原文
     */
    private void writeUserPolicy(String user, String yaml) throws Exception {
        Path file = home.resolve(user).resolve("jasagent").resolve("auth").resolve("policies.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    /**
     * 上下文里的 summary 是否仍是空串或 null，用来确认没把未完成压缩发布出去。
     *
     * @param body 上下文接口的 JSON
     * @return 摘要为空时为 true
     */
    private static boolean emptySummary(String body) {
        return body.contains("\"summary\":\"\"") || body.contains("\"summary\":null");
    }

    /**
     * 从上下文 JSON 里解析 occupancy 数字。字段缺失或不是数字时断言失败。
     *
     * @param body 上下文接口的 JSON
     * @return 占用比例
     */
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

    /**
     * 要求创建会话的响应是 200，再取出 sessionId。
     *
     * @param response 创建会话的响应
     * @return 会话编号
     */
    private static String sessionId(ResponseEntity<String> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "sessionId");
    }

    /**
     * 要求提交执行的响应是 202，再取出 executionId。
     *
     * @param response 提交执行的响应
     * @return 执行编号
     */
    private static String executionId(ResponseEntity<String> response) {
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "executionId");
    }

    /**
     * 从 JSON 正文取出一个字符串字段。
     *
     * @param body 响应正文
     * @param name 字段名
     * @return 字段值
     */
    private static String field(String body, String name) {
        String marker = "\"" + name + "\":\"";
        int start = body.indexOf(marker);
        assertTrue(start >= 0, body);
        start += marker.length();
        return body.substring(start, body.indexOf('"', start));
    }

    /**
     * 创建带给定前缀的临时目录。
     * 副作用：在系统临时目录留下这个文件夹。
     *
     * @param name 目录名前缀
     * @return 新建目录
     */
    private static Path create(String name) {
        try {
            return Files.createTempDirectory(name);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
