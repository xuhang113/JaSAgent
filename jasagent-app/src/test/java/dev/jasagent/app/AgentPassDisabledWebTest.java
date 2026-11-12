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

/**
 * 在通行证被配置关掉的网页里，确认 rag.search 会记成跳过并真正检索，而不是停在待批准。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentPassDisabledWebTest {
    /**
     * 本类共用的临时家目录。类加载时创建，里面放测试用户。
     */
    private static final Path home = create("jasagent-pass-off-home");
    /**
     * 本类共用的临时工作区。类加载时创建，call-rag 工作流写在这里。
     */
    private static final Path workspace = create("jasagent-pass-off-ws");

    @Autowired
    TestRestTemplate http;

    @Autowired
    LocalAuthService auth;

    /**
     * 把家目录、工作区白名单和本机地址写进测试上下文，并把 jasagent.agentpass.enabled 固定为 false。
     * 副作用：之后启动的服务不再要求工具批准。
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
        registry.add("jasagent.agentpass.enabled", () -> "false");
    }

    /**
     * 提交 call-rag 后等到成功。详情里的决定是 SKIPPED，原因含 agentpass-not-enabled，检索有命中，不能出现 PENDING 或 approval-required。
     * 副作用：写工作流文件、创建用户并访问本机接口。断言失败则测试失败。
     */
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

    /**
     * 先建用户，再用表单登录，返回去掉属性后的会话 Cookie。
     * 副作用：在家目录新增该用户。
     *
     * @param user 登录名
     * @param password 口令
     * @return 可放进 Cookie 请求头的名值对
     */
    private String login(String user, String password) throws Exception {
        auth.addUser(new AddUser(user, password.toCharArray(), null, false)).toCompletableFuture().join();
        return user;
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
     * 带会话 Cookie 发送 GET，不附正文。
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
     * 从 JSON 正文里取出一个字符串字段的值，找不到时让断言失败。
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
     * 创建带给定前缀的临时目录。失败时抛出非法状态异常。
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
