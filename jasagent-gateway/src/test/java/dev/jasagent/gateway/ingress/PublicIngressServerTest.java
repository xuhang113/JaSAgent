package dev.jasagent.gateway.ingress;

import dev.jasagent.it.UnsetTokenConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在真实 Tomcat 上检查相对跳转和 Secure。测试把回环当成受信任的上一跳，生产默认名单仍是空的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "jasagent.gateway.forwarded.trusted=127.0.0.1/32,::1/128")
@Import({UnsetTokenConfiguration.class, ForwardedObservationConfiguration.class})
class PublicIngressServerTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    /**
     * 准备临时用户和仓库外口令文件。信任名单由本类的属性指定为回环。
     *
     * @param registry 测试属性
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        IngressFixtures.register(registry);
    }

    /**
     * 回环明文、没有转发头时不加 Secure。站点根、登录页和未登录控制台的 Location 都是相对路径。
     */
    @Test
    void plainHttpKeepsRelativeRedirectsAndOmitsSecure() throws Exception {
        HttpResponse<String> root = send(get("/"));
        assertEquals(302, root.statusCode());
        assertEquals("/admin/", header(root, "location"));
        assertObserved(root, "http", "false", "");

        HttpResponse<String> loginPage = send(get("/login"));
        assertEquals(302, loginPage.statusCode());
        assertEquals("/admin/login", header(loginPage, "location"));
        assertFalse(header(loginPage, "location").contains("127.0.0.1"));

        HttpResponse<String> console = send(get("/admin/"));
        assertEquals(302, console.statusCode());
        assertEquals("/admin/login", header(console, "location"));

        HttpResponse<String> login = send(post("/login", null));
        assertEquals(200, login.statusCode());
        SessionCookies.assertSession(header(login, "set-cookie"), false, false, IngressFixtures.PASSWORD);
        assertTrue(header(login, "location") == null || header(login, "location").startsWith("/"));
    }

    /**
     * 受信任的回环声明 https 时，真实容器会给签发和清除的 Cookie 加上 Secure，但 Location 仍不写成绝对地址。
     */
    @Test
    void trustedLoopbackHttpsSetsSecureWithoutAbsoluteLocation() throws Exception {
        HttpResponse<String> root = send(get("/").header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "evil.example"));
        assertEquals(302, root.statusCode());
        assertEquals("/admin/", header(root, "location"));
        assertObserved(root, "https", "true", "https");
        assertFalse(header(root, "location").contains("evil.example"));
        assertFalse(header(root, "location").contains("http://"));
        assertFalse(header(root, "location").contains("https://"));

        HttpResponse<String> loginPage = send(get("/login").header("X-Forwarded-Proto", "http, https")
                .header("X-Forwarded-Host", "evil.example"));
        assertEquals("/admin/login", header(loginPage, "location"));

        HttpResponse<String> console = send(get("/admin/").header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "evil.example"));
        assertEquals("/admin/login", header(console, "location"));
        assertFalse(header(console, "location").contains("127.0.0.1"));

        HttpResponse<String> login = send(post("/login", "https"));
        assertEquals(200, login.statusCode());
        SessionCookies.assertSession(header(login, "set-cookie"), true, false, IngressFixtures.PASSWORD);
        assertObserved(login, "https", "true", "https");

        HttpResponse<String> logout = send(post("/logout", "https"));
        assertEquals(204, logout.statusCode());
        SessionCookies.assertSession(header(logout, "set-cookie"), true, true, IngressFixtures.PASSWORD);
    }

    /**
     * 构造一个不跟随跳转的 GET。
     *
     * @param path 以 / 开头的路径
     * @return 请求构造器
     */
    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
    }

    /**
     * 构造登录或退出。proto 为空时不带转发头。
     *
     * @param path /login 或 /logout
     * @param proto X-Forwarded-Proto；没有时为空
     * @return 请求构造器
     */
    private HttpRequest.Builder post(String path, String proto) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("user=" + IngressFixtures.LOGIN + "&password=" + IngressFixtures.PASSWORD));
        if (proto != null) {
            builder.header("X-Forwarded-Proto", proto);
            builder.header("X-Forwarded-Host", "evil.example");
        }
        return builder;
    }

    /**
     * 发送请求并保留跳转和错误正文，方便失败时判断。
     *
     * @param builder 请求
     * @return 响应
     */
    private static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 读取一个头的第一个值。头名按 HTTP 客户端保存的小写比较。
     *
     * @param response 响应
     * @param name 头名
     * @return 第一个值；没有时为空
     */
    private static String header(HttpResponse<String> response, String name) {
        List<String> values = response.headers().allValues(name);
        if (values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    /**
     * 真实容器上的观察头应和转发头过滤器的认定一致。
     *
     * @param response 响应
     * @param scheme 期望协议
     * @param secure 期望是否安全
     * @param proto 期望还能看见的协议头
     */
    private static void assertObserved(HttpResponse<String> response, String scheme, String secure, String proto) {
        assertEquals("true", header(response, ForwardedObservationConfiguration.ForwardedObservationFilter.APPLIED));
        assertEquals(scheme, header(response, ForwardedObservationConfiguration.ForwardedObservationFilter.SCHEME));
        assertEquals(secure, header(response, ForwardedObservationConfiguration.ForwardedObservationFilter.SECURE));
        assertEquals(proto, header(response, ForwardedObservationConfiguration.ForwardedObservationFilter.PROTO));
        String remote = header(response, ForwardedObservationConfiguration.ForwardedObservationFilter.REMOTE);
        assertTrue("127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote), remote);
    }
}
