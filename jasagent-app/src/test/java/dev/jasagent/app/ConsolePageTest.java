package dev.jasagent.app;

import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.LocalAuthService;
import org.junit.jupiter.api.Test;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsolePageTest {
    private static final Path home = create("jasagent-console-home");
    private static final Path workspace = create("jasagent-console-ws");

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
    void healthReportsStorageUp() {
        ResponseEntity<String> response = http.getForEntity("/health", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"status\":\"ok\""), response.getBody());
        assertTrue(response.getBody().contains("\"storage\":\"up\""), response.getBody());
    }

    @Test
    void loginPageReferencesVueBuild() {
        String body = page("/login");
        assertTrue(body.contains("id=\"app\""));
        assertTrue(body.contains("/assets/"));
    }

    @Test
    void consoleScriptHasCliAndChat() {
        String script = consoleScript();
        assertTrue(script.contains("CLI"));
        assertTrue(script.contains("对话"));
    }

    @Test
    void consoleScriptHasContextButton() {
        assertTrue(consoleScript().contains("上下文"));
    }

    @Test
    void consoleScriptHasPassActions() {
        assertTrue(consoleScript().contains("通行证"));
    }

    @Test
    void consoleScriptHasRetrieval() {
        assertTrue(consoleScript().contains("溯源"));
    }

    @Test
    void consoleScriptHasEvaluation() {
        assertTrue(consoleScript().contains("评估"));
    }

    @Test
    void consoleScriptHasSseEcho() {
        assertTrue(consoleScript().contains("SSE"));
    }

    @Test
    void consoleScriptShowsExecutionError() {
        String script = consoleScript();
        assertTrue(script.contains("execution-error"), "页面没有显示执行错误");
        assertTrue(script.contains("errorCode"), script);
    }

    @Test
    void consoleScriptUsesLiveEndpoints() {
        String script = consoleScript();
        assertTrue(script.contains("EventSource"), "控制台脚本没有 EventSource");
        assertTrue(script.contains("/api/executions/"), "控制台脚本没有执行接口");
        assertTrue(script.contains("/interrupt"), "控制台脚本没有中止接口");
        assertTrue(script.contains("/resume"), "控制台脚本没有恢复接口");
        assertTrue(script.contains("/api/sessions"), "控制台脚本没有会话接口");
        assertTrue(script.contains("/api/skills"), "控制台脚本没有技能接口");
        assertTrue(script.contains("/skill:"), "控制台脚本没有技能引用");
        assertTrue(script.contains("vue"), "控制台脚本不是 Vue 构建");
        assertTrue(script.contains("element-plus") || script.contains("el-button"), "控制台脚本没有 Element Plus");
    }

    @Test
    void workflowsIncludeAnswerWithTool() throws Exception {
        String cookie = login("wf-user", "test-password-1");
        ResponseEntity<String> response = get("/api/workflows", cookie);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("answer-with-tool"));
    }

    @Test
    void skillsStayInsideCurrentUser() throws Exception {
        String alpha = login("skill-a", "test-password-1");
        String beta = login("skill-b", "test-password-1");
        Path file = home.resolve("skill-a").resolve("jasagent").resolve("skills").resolve("alpha-skill").resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                ---
                name: alpha-skill
                description: 只给甲
                ---

                甲可见
                """);
        String seen = get("/api/skills", alpha).getBody();
        String hidden = get("/api/skills", beta).getBody();
        assertTrue(seen.contains("alpha-skill"));
        assertFalse(hidden.contains("alpha-skill"));
    }

    @Test
    void sessionListHidesOtherUsers() throws Exception {
        String alpha = login("sess-a", "test-password-1");
        String beta = login("sess-b", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "甲的会话")));
        assertTrue(get("/api/sessions", alpha).getBody().contains(sessionId));
        assertFalse(get("/api/sessions", beta).getBody().contains(sessionId));
    }

    @Test
    void ownerCanDeleteSession() throws Exception {
        String cookie = login("del-a", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "待删除")));
        ResponseEntity<String> deleted = exchange("/api/sessions/" + sessionId, HttpMethod.DELETE, cookie, null);
        assertEquals(HttpStatus.OK, deleted.getStatusCode());
        assertFalse(get("/api/sessions", cookie).getBody().contains(sessionId));
    }

    @Test
    void otherUserCannotReadSession() throws Exception {
        String alpha = login("read-a", "test-password-1");
        String beta = login("read-b", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "甲的私有会话")));
        ResponseEntity<String> response = get("/api/sessions/" + sessionId, beta);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
    }

    @Test
    void otherUserCannotDeleteSession() throws Exception {
        String alpha = login("keep-a", "test-password-1");
        String beta = login("keep-b", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "不能被乙删")));
        ResponseEntity<String> response = exchange("/api/sessions/" + sessionId, HttpMethod.DELETE, beta, null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(get("/api/sessions", alpha).getBody().contains(sessionId));
    }

    @Test
    void ownerCanReadCorrection() throws Exception {
        String cookie = login("note-a", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "纠正")));
        post("/api/sessions/" + sessionId + "/corrections", cookie, Map.of("text", "不要编造数字"));
        String listed = get("/api/sessions/" + sessionId + "/corrections", cookie).getBody();
        assertTrue(listed.contains("不要编造数字"));
    }

    @Test
    void otherUserCannotReadCorrection() throws Exception {
        String alpha = login("note-c", "test-password-1");
        String beta = login("note-d", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "纠正隔离")));
        post("/api/sessions/" + sessionId + "/corrections", alpha, Map.of("text", "只给甲看"));
        ResponseEntity<String> response = get("/api/sessions/" + sessionId + "/corrections", beta);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
    }

    @Test
    void executionDetailListsTools() throws Exception {
        String cookie = login("tool-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi")));
        String detail = get("/api/executions/" + executionId, cookie).getBody();
        assertTrue(detail.contains("\"tools\""));
        assertTrue(detail.contains("clock"));
        assertTrue(detail.contains("rag.search"));
        assertTrue(detail.contains("list_directory"));
        assertTrue(detail.contains("read_file"));
        assertTrue(detail.contains("run_shell_command"));
    }

    @Test
    void executionDetailListsPlugin() throws Exception {
        String cookie = login("plug-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi")));
        assertTrue(get("/api/executions/" + executionId, cookie).getBody().contains("grounding.default"));
    }

    @Test
    void defaultPassIsPendingNotSkipped() throws Exception {
        String cookie = login("pending-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi")));
        String detail = awaitTerminalStatus(cookie, executionId);
        assertFalse(detail.contains("\"status\":\"SUSPENDED\""), detail);
        assertTrue(detail.contains("\"status\":\"SUCCEEDED\"") || detail.contains("\"status\":\"FAILED\""), detail);
        assertTrue(detail.contains("\"decision\":\"PENDING\""), detail);
        assertTrue(detail.contains("approval-required"), detail);
        assertTrue(detail.contains("rag.search"), detail);
        assertTrue(detail.contains("grounding.default"), detail);
        assertFalse(detail.contains("\"decision\":\"SKIPPED\""), detail);
        assertFalse(detail.contains("agentpass-not-enabled"), detail);
        assertFalse(detail.contains("\"name\":\"clock\""), detail);
    }

    @Test
    void unmatchedPassDoesNotRunUntilAllowed() throws Exception {
        writeCallRag();
        writeWorkspacePolicy("""
                rules:
                - tool: rag.search
                  decision: ALLOW
                """);
        String cookie = login("pending-run", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "call-rag", "input", "hello")));
        String waiting = awaitBody(cookie, executionId, "terminal SUSPENDED");
        assertTrue(waiting.contains("\"retrievals\":[]"), waiting);
        assertTrue(waiting.contains("\"decision\":\"PENDING\""), waiting);
        assertTrue(waiting.contains("approval-required"), waiting);
        assertFalse(waiting.contains("agentpass-not-enabled"), waiting);
        String passId = passId(waiting, "rag.search");
        post("/api/executions/" + executionId + "/passes/" + passId, cookie, Map.of("decision", "ALLOW"));
        String done = awaitBody(cookie, executionId, "\"status\":\"SUCCEEDED\"");
        assertTrue(done.contains("\"retrievals\":[{"), done);
        assertTrue(done.contains("\"decision\":\"ALLOW\""), done);
        assertFalse(done.contains("\"decision\":\"PENDING\""), done);
    }

    @Test
    void matchedPolicyAllowRunsTool() throws Exception {
        writeCallRag();
        String cookie = login("policy-allow", "test-password-1");
        selectWorkspace(cookie);
        writeUserPolicy("policy-allow", """
                rules:
                - tool: rag.search
                  decision: ALLOW
                """);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "call-rag", "input", "hello")));
        String detail = awaitBody(cookie, executionId, "\"status\":\"SUCCEEDED\"");
        assertTrue(detail.contains("\"decision\":\"ALLOW\""), detail);
        assertTrue(detail.contains("\"retrievals\":[{"), detail);
        assertFalse(detail.contains("\"decision\":\"PENDING\""), detail);
        assertFalse(detail.contains("agentpass-not-enabled"), detail);
    }

    @Test
    void matchedPolicyDenyDoesNotRunTool() throws Exception {
        writeCallRag();
        String cookie = login("policy-deny", "test-password-1");
        selectWorkspace(cookie);
        writeUserPolicy("policy-deny", """
                rules:
                - tool: rag.search
                  decision: DENY
                """);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "call-rag", "input", "hello")));
        String detail = awaitBody(cookie, executionId, "\"status\":\"SUCCEEDED\"");
        assertTrue(detail.contains("\"decision\":\"DENY\""), detail);
        assertTrue(detail.contains("\"retrievals\":[]"), detail);
        assertFalse(detail.contains("\"decision\":\"PENDING\""), detail);
        assertFalse(detail.contains("agentpass-not-enabled"), detail);
    }

    @Test
    void sseEchoesExecutionStatus() throws Exception {
        writeCallRag();
        String cookie = login("sse-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "call-rag", "input", "hello")));
        awaitBody(cookie, executionId, "terminal SUSPENDED");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/executions/" + executionId + "/events"))
                .timeout(Duration.ofSeconds(20))
                .header("Cookie", cookie)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("event:status"), response.body());
        assertTrue(response.body().contains(executionId), response.body());
        assertTrue(response.body().contains("SUSPENDED"), response.body());
    }

    @Test
    void interruptThenResume() throws Exception {
        writeCallRag();
        String cookie = login("stop-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "call-rag", "input", "hello")));
        awaitBody(cookie, executionId, "terminal SUSPENDED");
        ResponseEntity<String> stopped = post("/api/executions/" + executionId + "/interrupt", cookie, Map.of());
        assertEquals(HttpStatus.OK, stopped.getStatusCode());
        assertTrue(stopped.getBody().contains("SUSPENDED"), stopped.getBody());
        ResponseEntity<String> resumed = post("/api/executions/" + executionId + "/resume", cookie, Map.of());
        assertEquals(HttpStatus.OK, resumed.getStatusCode());
        assertTrue(resumed.getBody().contains("resume"), resumed.getBody());
        assertTrue(resumed.getBody().contains(executionId), resumed.getBody());
    }

    @Test
    void passDenyIsStored() throws Exception {
        String cookie = login("pass-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi")));
        String detail = get("/api/executions/" + executionId, cookie).getBody();
        String passId = passId(detail, "rag.search");
        post("/api/executions/" + executionId + "/passes/" + passId, cookie, Map.of("decision", "DENY"));
        String updated = get("/api/executions/" + executionId, cookie).getBody();
        assertTrue(updated.contains("rag.search"));
        assertTrue(updated.contains("DENY"));
    }

    @Test
    void contextReturnsSessionId() throws Exception {
        String cookie = login("ctx-user", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "上下文")));
        ResponseEntity<String> response = get("/api/sessions/" + sessionId + "/context", cookie);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains(sessionId));
    }

    @Test
    void newSessionUsesPlaceholderUntilFirstMessage() throws Exception {
        String cookie = login("title-new", "test-password-1");
        assertEquals("新会话", field(post("/api/sessions", cookie, Map.of("title", "会话 1")).getBody(), "title"));
        assertEquals("新会话", field(post("/api/sessions", cookie, Map.of("title", "  新会话 2  ")).getBody(), "title"));
        assertEquals("新会话", field(post("/api/sessions", cookie, Map.of("title", "   ")).getBody(), "title"));
        assertEquals("甲的会话", field(post("/api/sessions", cookie, Map.of("title", " 甲的会话 ")).getBody(), "title"));
    }

    @Test
    void ownerCanRenameSession() throws Exception {
        String cookie = login("title-rename", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "待改名")));
        ResponseEntity<String> renamed = patch("/api/sessions/" + sessionId, cookie, Map.of("title", "  手改标题  "));
        assertEquals(HttpStatus.OK, renamed.getStatusCode());
        assertEquals("手改标题", field(renamed.getBody(), "title"));
        assertEquals("手改标题", field(get("/api/sessions/" + sessionId, cookie).getBody(), "title"));
    }

    @Test
    void otherUserCannotRenameSession() throws Exception {
        String alpha = login("title-a", "test-password-1");
        String beta = login("title-b", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "甲的标题")));
        ResponseEntity<String> response = patch("/api/sessions/" + sessionId, beta, Map.of("title", "乙改的"));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
        assertEquals("甲的标题", field(get("/api/sessions/" + sessionId, alpha).getBody(), "title"));
    }

    @Test
    void blankRenameKeepsTitle() throws Exception {
        String cookie = login("title-blank", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "原标题")));
        ResponseEntity<String> response = patch("/api/sessions/" + sessionId, cookie, Map.of("title", "   "));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("原标题", field(get("/api/sessions/" + sessionId, cookie).getBody(), "title"));
    }

    @Test
    void firstMessageSummarizesTitleWithoutReplacingEdits() throws Exception {
        String cookie = login("title-msg", "test-password-1");
        selectWorkspace(cookie);
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "新会话")));
        ResponseEntity<String> empty = post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", "   ", "workflowId", "answer-with-tool"));
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());
        assertEquals("新会话", field(get("/api/sessions/" + sessionId, cookie).getBody(), "title"));

        String text = "  请把这段话当成标题\n下一行  ";
        post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", text, "workflowId", "answer-with-tool"));
        assertEquals("请把这段话当成标题 下一行", field(get("/api/sessions/" + sessionId, cookie).getBody(), "title"));

        post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", "后来的另一句不该覆盖", "workflowId", "answer-with-tool"));
        assertEquals("请把这段话当成标题 下一行", field(get("/api/sessions/" + sessionId, cookie).getBody(), "title"));

        String custom = sessionId(post("/api/sessions", cookie, Map.of("title", "甲的会话")));
        post("/api/sessions/" + custom + "/messages", cookie, Map.of("text", "别覆盖自定义标题", "workflowId", "answer-with-tool"));
        assertEquals("甲的会话", field(get("/api/sessions/" + custom, cookie).getBody(), "title"));

        String renamed = sessionId(post("/api/sessions", cookie, Map.of("title", "新会话")));
        patch("/api/sessions/" + renamed, cookie, Map.of("title", "手改之后"));
        post("/api/sessions/" + renamed + "/messages", cookie, Map.of("text", "这条不该变成标题", "workflowId", "answer-with-tool"));
        assertEquals("手改之后", field(get("/api/sessions/" + renamed, cookie).getBody(), "title"));

        String longId = sessionId(post("/api/sessions", cookie, Map.of("title", "新会话")));
        String longText = "甲".repeat(50);
        post("/api/sessions/" + longId + "/messages", cookie, Map.of("text", longText, "workflowId", "answer-with-tool"));
        assertEquals("甲".repeat(40), field(get("/api/sessions/" + longId, cookie).getBody(), "title"));
    }

    @Test
    void consoleScriptEditsSessionTitle() {
        String script = consoleScript();
        assertTrue(script.contains("改名"), script);
        assertTrue(script.contains("PATCH"), script);
        assertTrue(script.contains("新会话"), script);
        assertFalse(script.contains("新会话 \" +") || script.contains("新会话 ' +"), script);
    }

    @Test
    void messageCreatesExecution() throws Exception {
        String cookie = login("msg-user", "test-password-1");
        selectWorkspace(cookie);
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "对话")));
        ResponseEntity<String> response = post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", "请回答", "workflowId", "answer-with-tool"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("executionId"));
        assertFalse(response.getBody().contains("\"executionId\":null"));
    }

    @Test
    void profileReturnsLogin() throws Exception {
        String cookie = login("me-user", "test-password-1");
        ResponseEntity<String> response = get("/api/me", cookie);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("me-user"));
    }

    private String consoleScript() {
        String html = page("/login");
        int cursor = 0;
        while (cursor >= 0) {
            int src = html.indexOf("/assets/", cursor);
            assertTrue(src > 0, "登录页没有引用 /assets/ 脚本");
            int end = html.indexOf('"', src);
            int quote = html.indexOf('\'', src);
            if (end < 0 || (quote >= 0 && quote < end)) {
                end = quote;
            }
            assertTrue(end > src, html);
            String path = html.substring(src, end);
            if (path.endsWith(".js")) {
                String body = http.getForEntity(path, String.class).getBody();
                assertNotNull(body);
                return body;
            }
            cursor = end;
        }
        throw new AssertionError("登录页没有引用 /assets/ 脚本");
    }

    private String page(String path) {
        String body = http.getForEntity(path, String.class).getBody();
        assertNotNull(body);
        return body;
    }

    private String login(String user, String password) throws Exception {
        ensureUser(user, password);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = http.exchange("/login", HttpMethod.POST, new HttpEntity<>("user=" + user + "&password=" + password, headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";", 2)[0];
    }

    private void selectWorkspace(String cookie) {
        ResponseEntity<String> response = post("/api/workspace", cookie, Map.of("path", workspace.toString()));
        assertTrue(response.getStatusCode().is2xxSuccessful());
    }

    private ResponseEntity<String> post(String path, String cookie, Map<String, String> body) {
        return exchange(path, HttpMethod.POST, cookie, body);
    }

    private ResponseEntity<String> patch(String path, String cookie, Map<String, String> body) {
        return exchange(path, HttpMethod.PATCH, cookie, body);
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

    private void ensureUser(String user, String password) throws Exception {
        char[] secret = password.toCharArray();
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

    private static String sessionId(ResponseEntity<String> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return field(response.getBody(), "sessionId");
    }

    private static String executionId(ResponseEntity<String> response) {
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        return field(response.getBody(), "executionId");
    }

    private static String passId(String body, String toolName) {
        int name = body.indexOf("\"name\":\"" + toolName + "\"");
        assertTrue(name > 0, body);
        int marker = body.lastIndexOf("\"passId\":\"", name);
        assertTrue(marker >= 0, body);
        return field(body.substring(marker), "passId");
    }

    private static String field(String body, String name) {
        String marker = "\"" + name + "\":\"";
        int start = body.indexOf(marker);
        assertTrue(start >= 0, body);
        start += marker.length();
        return body.substring(start, body.indexOf('"', start));
    }

    private String awaitTerminalStatus(String cookie, String executionId) throws Exception {
        String body = "";
        for (int attempt = 0; attempt < 300; attempt++) {
            body = get("/api/executions/" + executionId, cookie).getBody();
            if (body != null && (body.contains("\"status\":\"SUCCEEDED\"")
                    || body.contains("\"status\":\"FAILED\"")
                    || body.contains("\"status\":\"CANCELLED\"")
                    || body.contains("\"status\":\"SUSPENDED\""))) {
                return body;
            }
            Thread.sleep(50);
        }
        assertTrue(body != null && (body.contains("\"status\":\"SUCCEEDED\"") || body.contains("\"status\":\"FAILED\"")), body);
        return body == null ? "" : body;
    }

    private String awaitBody(String cookie, String executionId, String marker) throws Exception {
        String body = "";
        for (int attempt = 0; attempt < 300; attempt++) {
            body = get("/api/executions/" + executionId, cookie).getBody();
            if (body != null && body.contains(marker)) {
                return body;
            }
            Thread.sleep(50);
        }
        assertTrue(body != null && body.contains(marker), body);
        return body;
    }

    private void writeCallRag() throws Exception {
        Path dir = workspace.resolve("jasagent").resolve("workflows");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("call-rag.json"), """
                {"id":"call-rag","nodes":[{"id":"search","type":"tool","tool":"rag.search","arguments":"hello","next":"done"},{"id":"done","type":"end"}]}
                """);
    }

    private void writeWorkspacePolicy(String yaml) throws Exception {
        Path file = workspace.resolve("jasagent").resolve("auth").resolve("policies.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    private void writeUserPolicy(String user, String yaml) throws Exception {
        Path file = home.resolve(user).resolve("jasagent").resolve("auth").resolve("policies.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    private static Path create(String name) {
        try {
            return Files.createTempDirectory(name);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
