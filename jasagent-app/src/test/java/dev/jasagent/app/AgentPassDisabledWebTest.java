package dev.jasagent.app;

import dev.jasagent.api.auth.AddUser;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentPassDisabledWebTest {
    private static final Path home = create("jasagent-pass-off-home");
    private static final Path workspace = create("jasagent-pass-off-ws");

    @Autowired
    TestRestTemplate http;

    @Autowired
    LocalAuthService auth;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jasagent.home", () -> home.toString());
        registry.add("jasagent.workspaces", () -> workspace.toString());
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("jasagent.agentpass.enabled", () -> "false");
    }

    @Test
    void disabledPassIsSkippedAndRunsTool() throws Exception {
        Path dir = workspace.resolve("jasagent").resolve("workflows");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("call-rag.json"), """
                {"id":"call-rag","nodes":[{"id":"search","type":"tool","tool":"rag.search","arguments":"hello","next":"done"},{"id":"done","type":"end"}]}
                """);
        String cookie = login("off-user", "test-password-1");
        ResponseEntity<String> selected = post("/api/workspace", cookie, Map.of("path", workspace.toString()));
        assertTrue(selected.getStatusCode().is2xxSuccessful());
        ResponseEntity<String> created = post("/api/executions", cookie, Map.of("workflowId", "call-rag", "input", "hello"));
        assertEquals(HttpStatus.ACCEPTED, created.getStatusCode());
        String executionId = field(created.getBody(), "executionId");
        String detail = "";
        for (int attempt = 0; attempt < 80; attempt++) {
            detail = get("/api/executions/" + executionId, cookie).getBody();
            if (detail != null && detail.contains("\"status\":\"SUCCEEDED\"")) {
                break;
            }
            Thread.sleep(40);
        }
        assertTrue(detail.contains("\"status\":\"SUCCEEDED\""), detail);
        assertTrue(detail.contains("\"decision\":\"SKIPPED\""), detail);
        assertTrue(detail.contains("agentpass-not-enabled"), detail);
        assertTrue(detail.contains("\"retrievals\":[{"), detail);
        assertFalse(detail.contains("\"decision\":\"PENDING\""), detail);
        assertFalse(detail.contains("approval-required"), detail);
    }

    private String login(String user, String password) throws Exception {
        auth.addUser(new AddUser(user, password.toCharArray(), null, false)).toCompletableFuture().join();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = http.exchange("/login", HttpMethod.POST, new HttpEntity<>("user=" + user + "&password=" + password, headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";", 2)[0];
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
