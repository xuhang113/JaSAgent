package dev.jasagent.app;

import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebFlowTest {
    private static final Path home = create("jasagent-home");
    private static final Path workspace = create("jasagent-ws");

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

    private static Path create(String name) {
        try {
            return Files.createTempDirectory(name);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void anonymousSubmitIs401() {
        ResponseEntity<String> response = http.postForEntity("/api/executions", Map.of("workflowId", "answer-with-tool", "input", "hi"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
        assertTrue(response.getBody().contains("executionId"));
        assertFalse(response.getBody().contains("Exception"));
    }

    @Test
    void runWithoutWorkspaceRejected() throws Exception {
        String cookie = login("alpha", "test-password-1");
        ResponseEntity<String> response = post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi"));
        assertFalse(response.getStatusCode().value() == 202);
    }

    @Test
    void submitAfterWorkspaceReturns202() throws Exception {
        String cookie = login("beta", "test-password-1");
        selectWorkspace(cookie);
        ResponseEntity<String> response = post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi"));
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertTrue(response.getBody().contains("executionId"));
        assertFalse(response.getBody().contains("\"executionId\":null"));
    }

    @Test
    void listIsLimitedToCurrentUser() throws Exception {
        String alpha = login("gamma", "test-password-1");
        String beta = login("delta", "test-password-1");
        selectWorkspace(alpha);
        selectWorkspace(beta);
        String alphaId = id(post("/api/executions", alpha, Map.of("workflowId", "answer-with-tool", "input", "from-gamma")));
        String betaId = id(post("/api/executions", beta, Map.of("workflowId", "answer-with-tool", "input", "from-delta")));
        String listed = get("/api/executions", alpha).getBody();
        assertTrue(listed.contains(alphaId));
        assertFalse(listed.contains(betaId));
    }

    @Test
    void healthIsPublic() {
        assertEquals(HttpStatus.OK, http.getForEntity("/health", String.class).getStatusCode());
    }

    @Test
    void noHttpRegistration() throws Exception {
        String cookie = login("epsilon", "test-password-1");
        ResponseEntity<String> response = post("/api/users", cookie, Map.of("user", "other", "password", "test-password-1"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void loginPageHasForm() {
        String body = http.getForEntity("/login", String.class).getBody();
        assertNotNull(body);
        assertTrue(body.contains("登录"));
        assertTrue(body.contains("password"));
    }

    private String login(String user, String password) throws Exception {
        ensureUser(user, password);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = http.exchange("/login", HttpMethod.POST, new HttpEntity<>("user=" + user + "&password=" + password, headers), String.class);
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertNotNull(cookies);
        return cookies.get(0).split(";", 2)[0];
    }

    private void selectWorkspace(String cookie) {
        ResponseEntity<String> response = post("/api/workspace", cookie, Map.of("path", workspace.toString()));
        assertTrue(response.getStatusCode().is2xxSuccessful());
    }

    private ResponseEntity<String> post(String path, String cookie, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
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

    private static String id(ResponseEntity<String> response) {
        String body = response.getBody();
        int marker = body.indexOf("\"executionId\":\"");
        int start = marker + "\"executionId\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }
}
