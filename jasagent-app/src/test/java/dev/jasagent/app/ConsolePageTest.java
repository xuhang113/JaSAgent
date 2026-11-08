package dev.jasagent.app;

import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.LocalAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsolePageTest {
    private static final Path home = create("jasagent-console-home");
    private static final Path workspace = create("jasagent-console-ws");

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
    }

    @Test
    void executionDetailListsPlugin() throws Exception {
        String cookie = login("plug-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi")));
        assertTrue(get("/api/executions/" + executionId, cookie).getBody().contains("grounding.default"));
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

    private static Path create(String name) {
        try {
            return Files.createTempDirectory(name);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
