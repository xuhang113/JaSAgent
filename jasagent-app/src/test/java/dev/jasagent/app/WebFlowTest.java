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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebFlowTest {
    private static final Path home = create("jasagent-webflow-home");
    private static final Path workspace = create("jasagent-webflow-ws");
    private static final String PASSWORD = "test-password-1";

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
    void anonymousSubmitIs401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange(
                "/api/executions",
                HttpMethod.POST,
                new HttpEntity<>("{\"workflowId\":\"answer-with-tool\",\"input\":\"hi\"}", headers),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("errorCode"), body);
        assertFalse(body.contains("Exception"), body);
        assertFalse(body.contains("\tat "), body);
    }

    @Test
    void runWithoutWorkspaceRejected() throws Exception {
        String cookie = login("no-ws");
        ResponseEntity<String> response = post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi"));
        assertNotEquals(HttpStatus.ACCEPTED, response.getStatusCode(), response.getBody());
    }

    @Test
    void listIsLimitedToCurrentUser() throws Exception {
        String alpha = login("exec-a");
        String beta = login("exec-b");
        selectWorkspace(alpha);
        selectWorkspace(beta);
        String alphaId = executionId(post("/api/executions", alpha, Map.of("workflowId", "answer-with-tool", "input", "甲")));
        String betaId = executionId(post("/api/executions", beta, Map.of("workflowId", "answer-with-tool", "input", "乙")));
        String alphaList = get("/api/executions", alpha).getBody();
        String betaList = get("/api/executions", beta).getBody();
        assertNotNull(alphaList);
        assertNotNull(betaList);
        assertTrue(alphaList.contains(alphaId), alphaList);
        assertFalse(alphaList.contains(betaId), alphaList);
        assertTrue(betaList.contains(betaId), betaList);
        assertFalse(betaList.contains(alphaId), betaList);
    }

    @Test
    void noHttpRegistration() throws Exception {
        String cookie = login("no-reg");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>("{\"user\":\"another\",\"password\":\"test-password-1\"}", headers),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), response.getBody());
    }

    @Test
    void loginPageHasForm() {
        String body = http.getForEntity("/login", String.class).getBody();
        assertNotNull(body);
        assertTrue(body.contains("登录"), body);
        assertTrue(body.contains("password"), body);
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
