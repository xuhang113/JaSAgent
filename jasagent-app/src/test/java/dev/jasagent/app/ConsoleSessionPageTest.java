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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleSessionPageTest {
    private static final Path home = create("jasagent-session-home");
    private static final Path workspace = create("jasagent-session-ws");
    private static final String PASSWORD = "test-password-1";
    private static final String USER_TEXT = "请给出时间";
    private static final String RULE = "遵守事实";
    private static final String SKILL = "查找公开资料";

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
    void anonymousModelListIs401() {
        ResponseEntity<String> response = http.getForEntity("/api/models", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
    }

    @Test
    void consoleShowsModelSelect() {
        String script = consoleScript();
        assertTrue(script.contains("data-model-select"), scriptSnippet(script, "model"));
        assertTrue(script.contains("data-session-model"), script);
        String css = consoleCss();
        assertTrue(css.contains("model-select"), css);
        assertTrue(css.contains("#1b1b18"), css);
        assertTrue(css.contains("8px"), css);
    }

    @Test
    void sessionRemembersDeepseekFlash() throws Exception {
        String cookie = login("model-user");
        String first = sessionId(post("/api/sessions", cookie, Map.of("title", "甲")));
        String second = sessionId(post("/api/sessions", cookie, Map.of("title", "乙")));
        String firstBody = get("/api/sessions/" + first, cookie).getBody();
        String secondBody = get("/api/sessions/" + second, cookie).getBody();
        assertTrue(firstBody.contains("\"model\":\"deepseek-flash\""), firstBody);
        assertTrue(firstBody.contains("\"modelProvider\":\"deepseek\""), firstBody);
        assertTrue(secondBody.contains("\"model\":\"deepseek-flash\""), secondBody);
        assertTrue(get("/api/sessions/" + first, cookie).getBody().contains("\"model\":\"deepseek-flash\""));
    }

    @Test
    void unknownModelIsRejected() throws Exception {
        String cookie = login("model-reject");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "拒绝")));
        ResponseEntity<String> response = exchange(
                "/api/sessions/" + sessionId,
                HttpMethod.PATCH,
                cookie,
                Map.of("model", "not-a-real-model", "modelProvider", "deepseek"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("WORKFLOW"), response.getBody());
        assertTrue(get("/api/sessions/" + sessionId, cookie).getBody().contains("\"model\":\"deepseek-flash\""));
    }

    @Test
    void modelListUsesDeepseekDefault() throws Exception {
        String cookie = login("model-list");
        String body = get("/api/models", cookie).getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"defaultModel\":\"deepseek-flash\""), body);
        if (System.getenv("JASAGENT_GATEWAY_BASE_URL") == null
                || System.getenv("JASAGENT_GATEWAY_API_KEY") == null
                || System.getenv("JASAGENT_GATEWAY_MODEL") == null) {
            assertFalse(body.contains("ai-gateway"), body);
        }
    }

    @Test
    void anonymousSessionListIs401() {
        ResponseEntity<String> response = http.getForEntity("/api/sessions", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("errorCode"));
        assertFalse(body.contains("Exception"));
        assertFalse(body.contains("\tat "));
    }

    @Test
    void consoleShowsSessionAsMain() {
        String script = consoleScript();
        assertTrue(script.contains("session-main"), scriptSnippet(script, "session"));
        assertTrue(script.contains("子 agent"));
    }

    @Test
    void consoleShowsHarnessNote() {
        String script = consoleScript();
        assertTrue(script.contains("纠正 note"));
        assertTrue(script.contains("harness note"));
    }

    @Test
    void rightRailStaysOpen() {
        String script = consoleScript();
        assertTrue(script.contains("data-panels-open\":\"true\""), scriptSnippet(script, "data-panels-open"));
        assertTrue(script.contains("console-layout"), script);
        assertTrue(script.contains("right-rail"), script);
        assertTrue(script.contains("turn-trace"), script);
        assertFalse(script.contains("data-default-panels\":\"hidden\""), script);
        assertFalse(script.contains("data-panels-open\":\"false\""), script);
    }

    @Test
    void panelsCanResize() {
        assertTrue(consoleScript().contains("data-resize"));
    }

    @Test
    void sessionListsSubAgent() throws Exception {
        String cookie = login("agent-user");
        selectWorkspace(cookie);
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "子代理")));
        post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", "调度一次", "workflowId", "answer-with-tool"));
        String body = get("/api/sessions/" + sessionId, cookie).getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"role\":\"SUB\""), body);
    }

    @Test
    void sessionShowsCorrection() throws Exception {
        String cookie = login("note-user");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "纠正")));
        post("/api/sessions/" + sessionId + "/corrections", cookie, Map.of("text", "不要编造数字"));
        String body = get("/api/sessions/" + sessionId, cookie).getBody();
        assertNotNull(body);
        assertTrue(body.contains("不要编造数字"), body);
    }

    @Test
    void otherUserCannotReadSession() throws Exception {
        String alpha = login("own-a");
        String beta = login("own-b");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "甲的会话")));
        ResponseEntity<String> response = get("/api/sessions/" + sessionId, beta);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
    }

    @Test
    void timelineSystemContainsRule() throws Exception {
        String body = supportTimeline("rule-user");
        int system = body.indexOf("\"type\":\"system\"");
        assertTrue(system >= 0, body);
        int next = body.indexOf("\"type\":\"", system + 1);
        String message = next < 0 ? body.substring(system) : body.substring(system, next);
        assertTrue(message.contains(RULE), message);
    }

    @Test
    void timelineSystemContainsSkill() throws Exception {
        String body = supportTimeline("skill-user");
        int system = body.indexOf("\"type\":\"system\"");
        assertTrue(system >= 0, body);
        int next = body.indexOf("\"type\":\"", system + 1);
        String message = next < 0 ? body.substring(system) : body.substring(system, next);
        assertTrue(message.contains(SKILL), message);
    }

    @Test
    void timelineHasHuman() throws Exception {
        String body = supportTimeline("human-user");
        assertTrue(body.contains("\"type\":\"human\""), body);
        assertTrue(body.contains(USER_TEXT), body);
    }

    @Test
    void timelineHasAiReply() throws Exception {
        String body = supportTimeline("ai-user");
        assertTrue(body.contains("\"type\":\"ai\""), body);
        assertTrue(body.contains("模型未配置"), body);
        assertFalse(body.contains("已收到"), body);
    }

    @Test
    void timelineHasClockTool() throws Exception {
        String body = supportTimeline("tool-user");
        int name = body.indexOf("\"name\":\"clock\"");
        assertTrue(name > 0, body);
        int type = body.lastIndexOf("\"type\":\"tool\"", name);
        assertTrue(type >= 0 && type < name, body);
        int content = body.indexOf("\"content\":\"", name);
        assertTrue(content > name, body);
        String value = body.substring(content + "\"content\":\"".length(), Math.min(body.length(), content + 48));
        assertTrue(value.length() >= 11 && value.charAt(4) == '-' && value.contains("T"), value);
    }

    @Test
    void timelineOrderIsLangGraph() throws Exception {
        String body = supportTimeline("order-user");
        int system = body.indexOf("\"type\":\"system\"");
        int human = body.indexOf("\"type\":\"human\"");
        int tool = body.indexOf("\"type\":\"tool\"");
        int ai = body.indexOf("\"type\":\"ai\"");
        assertTrue(system >= 0 && system < human && human < tool && tool < ai, body);
    }

    @Test
    void otherUserCannotReadTimeline() throws Exception {
        String alpha = login("time-a");
        String beta = login("time-b");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "甲的时间线")));
        ResponseEntity<String> response = get("/api/sessions/" + sessionId + "/timeline", beta);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
    }

    @Test
    void compressAtPropertyDefaultsTo085() throws Exception {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(props.contains("jasagent.context.compress-at=0.85"), props);
    }

    @Test
    void contextReportsCompressAt() throws Exception {
        String cookie = login("ratio-user");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "阈值")));
        String body = get("/api/sessions/" + sessionId + "/context", cookie).getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"compressAt\":0.85"), body);
    }

    @Test
    void consoleKeepsInterrupt() {
        assertTrue(consoleScript().contains("中止"));
    }

    @Test
    void consoleKeepsResume() {
        assertTrue(consoleScript().contains("恢复"));
    }

    @Test
    void consoleKeepsSse() {
        assertTrue(consoleScript().contains("SSE"));
    }

    @Test
    void consoleKeepsSkillRef() {
        assertTrue(consoleScript().contains("/skill:"));
    }

    @Test
    void consoleKeepsPasses() {
        assertTrue(consoleScript().contains("通行证"));
    }

    @Test
    void messagesRuleIsCompact() {
        String css = consoleCss();
        int at = css.indexOf(".messages{");
        assertTrue(at >= 0, "样式里没有 .messages");
        int end = css.indexOf('}', at);
        assertTrue(end > at);
        String rule = css.substring(at, end);
        assertFalse(rule.contains("180px"), rule);
    }

    @Test
    void consoleUsesLangGraphTimeline() {
        assertTrue(consoleScript().contains("data-timeline\":\"langgraph\""));
    }

    @Test
    void consoleDropsAlwaysOnExecution() {
        assertFalse(consoleScript().contains("当前执行"));
    }

    @Test
    void consoleFoldsThinkingAboveReply() {
        String script = consoleScript();
        assertTrue(script.contains("思考"), scriptSnippet(script, "思考"));
        assertTrue(script.contains("thinking"), scriptSnippet(script, "thinking"));
        String css = consoleCss();
        int at = css.indexOf(".thinking{");
        assertTrue(at >= 0, css);
        int end = css.indexOf('}', at);
        assertTrue(end > at, css);
        String rule = css.substring(at, end);
        assertTrue(rule.contains("#98a2b3"), rule);
        assertTrue(rule.contains("12px"), rule);
        String props = readProps();
        assertTrue(props.contains("jasagent.deepseek.thinking=true"), props);
    }

    private static String readProps() {
        try {
            return Files.readString(Path.of("src/main/resources/application.properties"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String supportTimeline(String user) throws Exception {
        String cookie = login(user);
        selectWorkspace(cookie);
        Path rules = home.resolve(user).resolve("jasagent").resolve("rules");
        Files.createDirectories(rules);
        Files.writeString(rules.resolve("base.md"), """
                ---
                id: base
                description: %s
                ---
                只写能核对的话
                """.formatted(RULE));
        Path skill = home.resolve(user).resolve("jasagent").resolve("skills").resolve("research");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), """
                ---
                name: research
                description: %s
                ---
                先查再答
                """.formatted(SKILL));
        Path workflows = workspace.resolve("jasagent").resolve("workflows");
        Files.createDirectories(workflows);
        Files.writeString(workflows.resolve("show-support.json"), """
                {"id":"show-support","nodes":[{"id":"now","type":"tool","tool":"clock"},{"id":"act","type":"react","maxSteps":2,"tools":[],"grounding":false},{"id":"done","type":"end"}]}
                """);
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "支持")));
        ResponseEntity<String> sent = post("/api/sessions/" + sessionId + "/messages", cookie, Map.of(
                "text", USER_TEXT,
                "workflowId", "show-support"));
        assertEquals(HttpStatus.OK, sent.getStatusCode(), sent.getBody());
        String executionId = field(sent.getBody(), "executionId");
        awaitBody(cookie, executionId, "\"status\":\"FAILED\"");
        ResponseEntity<String> timeline = get("/api/sessions/" + sessionId + "/timeline?executionId=" + executionId, cookie);
        assertEquals(HttpStatus.OK, timeline.getStatusCode(), timeline.getBody());
        assertNotNull(timeline.getBody());
        return timeline.getBody();
    }

    private String consoleScript() {
        return asset(page("/login"), ".js");
    }

    private String consoleCss() {
        return asset(page("/login"), ".css");
    }

    private String asset(String html, String suffix) {
        int cursor = 0;
        while (cursor >= 0) {
            int src = html.indexOf("/assets/", cursor);
            assertTrue(src > 0, html);
            int end = html.indexOf('"', src);
            int quote = html.indexOf('\'', src);
            if (end < 0 || (quote >= 0 && quote < end)) {
                end = quote;
            }
            assertTrue(end > src, html);
            String path = html.substring(src, end);
            if (path.endsWith(suffix)) {
                String body = http.getForEntity(path, String.class).getBody();
                assertNotNull(body);
                return body;
            }
            cursor = end;
        }
        throw new AssertionError("登录页没有 " + suffix);
    }

    private String page(String path) {
        String body = http.getForEntity(path, String.class).getBody();
        assertNotNull(body);
        return body;
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
        assertEquals(HttpStatus.OK, response.getStatusCode());
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

    private static String sessionId(ResponseEntity<String> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "sessionId");
    }

    private static String field(String body, String name) {
        String marker = "\"" + name + "\":\"";
        int start = body.indexOf(marker);
        assertTrue(start >= 0, body);
        start += marker.length();
        return body.substring(start, body.indexOf('"', start));
    }

    private static String scriptSnippet(String script, String needle) {
        int at = script.indexOf(needle);
        if (at < 0) {
            return "没有 " + needle;
        }
        return script.substring(Math.max(0, at - 40), Math.min(script.length(), at + 80));
    }

    private static Path create(String name) {
        try {
            return Files.createTempDirectory(name);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
