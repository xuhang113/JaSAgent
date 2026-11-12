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

/**
 * 用随机端口启动网页，锁住未登录提交、未选工作区、执行列表只看自己、不能用 HTTP 注册，以及登录页上的表单文案。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebFlowTest {
    /**
     * 本类共用的临时家目录。类加载时创建，用户和口令都写在这里。
     */
    private static final Path home = create("jasagent-webflow-home");
    /**
     * 本类共用的临时工作区。类加载时创建，并作为允许选择的唯一工作区。
     */
    private static final Path workspace = create("jasagent-webflow-ws");
    /**
     * 本类测试用户共用的口令，只出现在夹具里，不是真实密钥。
     */
    private static final String PASSWORD = "test-password-1";

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
     * 不带 Cookie 提交执行，必须是 401。正文含 errorCode，不能出现 Exception 或堆栈里的 at。
     * 副作用：向本机接口发一次 POST。断言失败则测试失败。
     */
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

    /**
     * 登录后不选工作区就提交执行，响应不能是 202 ACCEPTED。
     * 副作用：可能创建用户并留下登录会话。断言失败则测试失败。
     */
    @Test
    void runWithoutWorkspaceRejected() throws Exception {
        String cookie = login("no-ws");
        ResponseEntity<String> response = post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi"));
        assertNotEquals(HttpStatus.ACCEPTED, response.getStatusCode(), response.getBody());
    }

    /**
     * 两个用户各自提交一次。每个人的执行列表只含自己的 executionId。
     * 副作用：创建两名用户、选择工作区并留下两次执行。断言失败则测试失败。
     */
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

    /**
     * 已登录时向 /api/users 发注册请求，必须是 404，用户不能从网页接口被创建。
     * 副作用：可能创建发起请求的那个用户。断言失败则测试失败。
     */
    @Test
    void noHttpRegistration() throws Exception {
        String cookie = login("no-reg");
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Jasagent-Token", "test-internal-token");
        headers.set("X-Jasagent-Tenant", "local");
        headers.set("X-Jasagent-User", cookie);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>("{\"user\":\"another\",\"password\":\"test-password-1\"}", headers),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), response.getBody());
    }

    /**
     * 打开 /login，正文里要有“登录”和 password，说明登录表单还在。
     * 副作用：请求登录页。断言失败则测试失败。
     */
    @Test
    void loginPageHasForm() {
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("/login", String.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("/", String.class).getStatusCode());
    }

    /**
     * 确保用户存在后用本类口令表单登录，返回去掉属性后的会话 Cookie。
     * 副作用：用户不存在时会在家目录建号。
     *
     * @param user 登录名
     * @return 可放进 Cookie 请求头的名值对
     */
    private String login(String user) throws Exception {
        ensureUser(user);
        return user;
    }

    /**
     * 替该会话选中本类的临时工作区，响应必须是 2xx。
     * 副作用：会话绑定到这个工作区。
     *
     * @param cookie 登录得到的 Cookie
     */
    private void selectWorkspace(String cookie) {
        ResponseEntity<String> response = post("/api/workspace", cookie, Map.of("path", workspace.toString()));
        assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
    }

    /**
     * 带会话 Cookie 发送 JSON POST。
     *
     * @param path 接口路径
     * @param cookie 登录得到的 Cookie
     * @param body 作为 JSON 发送的字段
     * @return 响应状态和正文字符串
     */
    private ResponseEntity<String> post(String path, String cookie, Map<String, String> body) {
        return exchange(path, HttpMethod.POST, cookie, body);
    }

    /**
     * 带会话 Cookie 发送 GET。
     *
     * @param path 接口路径
     * @param cookie 登录得到的 Cookie
     * @return 响应状态和正文字符串
     */
    private ResponseEntity<String> get(String path, String cookie) {
        return exchange(path, HttpMethod.GET, cookie, null);
    }

    /**
     * 带 Cookie 调用接口。正文不是 null 时声明 JSON。
     *
     * @param path 接口路径
     * @param method HTTP 方法
     * @param cookie 登录得到的 Cookie
     * @param body 请求字段，null 表示没有正文
     * @return 响应状态和正文字符串
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
     * 保证登录名可用。已能登录就返回；否则直接建用户；已有别的用户时，用其中能登录的账号当管理员来添加。
     * 副作用：可能在家目录新增用户。最终仍无法创建时抛出非法状态异常。
     *
     * @param user 需要存在的登录名
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
     * 从 JSON 正文里取出一个字符串字段的值。
     *
     * @param body 响应正文
     * @param name 字段名
     * @return 两个引号之间的字段值
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
