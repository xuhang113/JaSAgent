package dev.jasagent.gateway;

import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.it.UnsetTokenConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /admin/jaeger 在控制台反代之前按登录和 admin 拦截。上游只使用配置地址，登录会话不转发。
 */
@SpringBootTest(properties = "jasagent.gateway.jaeger-proxy-test=true")
@AutoConfigureMockMvc
@Import(UnsetTokenConfiguration.class)
class JaegerProxyTest {
    private static final String ADMIN = "ops" + UUID.randomUUID().toString().replace("-", "");
    private static final String ADMIN_SECRET = "pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final CopyOnWriteArrayList<Hit> JAEGER = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<String> WEB = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<String> EVIL = new CopyOnWriteArrayList<>();
    private static Path home;
    private static HttpServer web;
    private static HttpServer jaeger;
    private static HttpServer evil;

    @Autowired
    private MockMvc mvc;

    /**
     * 准备临时运维目录、控制台桩、Jaeger 桩，以及一台不该被请求改写到的桩。
     * 属性注册发生在测试方法之前，所以桩必须先听上。
     */
    private static void ensure() throws Exception {
        if (jaeger != null) {
            return;
        }
        home = Files.createTempDirectory("gw025-jaeger");
        new LocalAuthService(home).addUser(new AddUser(ADMIN, ADMIN_SECRET.toCharArray(), null, false)).toCompletableFuture().join();
        web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        web.createContext("/", exchange -> {
            WEB.add(exchange.getRequestURI().getPath());
            byte[] body = "web-page".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jaeger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jaeger.createContext("/", exchange -> {
            JAEGER.add(new Hit(
                    exchange.getRequestURI().getPath(),
                    String.valueOf(exchange.getRequestHeaders().getFirst("Host")),
                    String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")),
                    String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    String.valueOf(exchange.getRequestHeaders().getFirst("X-Forwarded-Host")),
                    String.valueOf(exchange.getRequestURI().getQuery())));
            byte[] body = "JAEGER-PAGE".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().add("Set-Cookie", "JASAGENT_SESSION=from-upstream");
            exchange.getResponseHeaders().add("Set-Cookie", "jaeger-ui=keep-ui");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        evil = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        evil.createContext("/", exchange -> {
            EVIL.add(exchange.getRequestURI().getPath());
            byte[] body = "EVIL".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        web.start();
        jaeger.start();
        evil.start();
    }

    /**
     * 把网关指到两台桩，并清掉进程里的内部令牌。
     *
     * @param registry 测试属性
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        ensure();
        registry.add("jasagent.home", () -> home.toString());
        registry.add("jasagent.gateway.links.enabled", () -> "false");
        registry.add("jasagent.gateway.web-origin", () -> "http://127.0.0.1:" + web.getAddress().getPort());
        registry.add("jasagent.gateway.jaeger-origin", () -> "http://127.0.0.1:" + jaeger.getAddress().getPort());
    }

    /**
     * 清掉上一轮访问。
     */
    @BeforeEach
    void reset() {
        JAEGER.clear();
        WEB.clear();
        EVIL.clear();
    }

    /**
     * 关掉三台桩。
     */
    @AfterAll
    static void stop() {
        if (web != null) {
            web.stop(0);
        }
        if (jaeger != null) {
            jaeger.stop(0);
        }
        if (evil != null) {
            evil.stop(0);
        }
    }

    /**
     * 未登录的 Jaeger 页面转到登录页，正文里没有 Jaeger 或控制台页面。
     */
    @Test
    void anonymousJaegerRedirectsToLogin() throws Exception {
        MvcResult result = mvc.perform(get("/admin/jaeger")).andExpect(status().isFound()).andReturn();
        assertTrue(result.getResponse().getHeader(HttpHeaders.LOCATION).endsWith("/admin/login"));
        assertFalse(result.getResponse().getContentAsString().contains("JAEGER-PAGE"));
        assertFalse(result.getResponse().getContentAsString().contains("web-page"));
        assertTrue(JAEGER.isEmpty());
        assertFalse(WEB.contains("/admin/jaeger"));
        MvcResult child = mvc.perform(get("/admin/jaeger/search")).andExpect(status().isFound()).andReturn();
        assertFalse(child.getResponse().getContentAsString().contains("JAEGER-PAGE"));
        assertTrue(JAEGER.isEmpty());
    }

    /**
     * 已登录的普通用户得到 403。正文没有 Jaeger 页面、控制台页面、口令或会话。
     */
    @Test
    void userCannotOpenJaeger() throws Exception {
        String secret = secret();
        String login = "u" + UUID.randomUUID().toString().replace("-", "");
        mvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=" + login + "&password=" + secret))
                .andExpect(status().isCreated());
        String cookie = login(login, secret);
        MvcResult result = mvc.perform(get("/admin/jaeger").cookie(session(cookie))).andExpect(status().isForbidden()).andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"errorCode\":\"FORBIDDEN\""));
        assertFalse(body.contains("JAEGER-PAGE"));
        assertFalse(body.contains("web-page"));
        assertFalse(body.contains(secret), "拒绝正文含有口令");
        assertFalse(body.contains(cookie), "拒绝正文含有会话");
        assertTrue(JAEGER.isEmpty());
        assertFalse(WEB.contains("/admin/jaeger"));
        mvc.perform(get("/admin/jaeger/search").cookie(session(cookie))).andExpect(status().isForbidden());
        assertTrue(JAEGER.isEmpty());
        assertTrue(EVIL.isEmpty());
    }

    /**
     * admin 打开 Jaeger 及其子路径时正文来自配置的上游，不来自控制台。
     * 登录会话不转发，上游的同名 Set-Cookie 不写回。请求里的主机和查询改不了上游地址。
     */
    @Test
    void adminIsProxiedWithoutSessionOrRewrittenHost() throws Exception {
        String cookie = login(ADMIN, ADMIN_SECRET);
        MvcResult page = mvc.perform(get("/admin/jaeger")
                        .cookie(session(cookie), new Cookie("jaeger-ui", "keep"))
                        .header("Authorization", "Bearer " + cookie)
                        .header("X-Forwarded-Host", "127.0.0.1:" + evil.getAddress().getPort())
                        .header("X-Jaeger-Upstream", "http://127.0.0.1:" + evil.getAddress().getPort()))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(page.getResponse().getContentAsString().contains("JAEGER-PAGE"));
        assertFalse(page.getResponse().getContentAsString().contains("web-page"));
        assertFalse(WEB.contains("/admin/jaeger"));
        assertEquals(1, JAEGER.size());
        Hit hit = JAEGER.get(0);
        assertEquals("/admin/jaeger", hit.path());
        assertTrue(hit.host().endsWith(":" + jaeger.getAddress().getPort()), "上游主机被改写");
        assertFalse(hit.host().contains(String.valueOf(evil.getAddress().getPort())), "上游主机被改写");
        assertFalse(hit.cookie().contains(cookie), "登录会话被转发");
        assertFalse(hit.cookie().contains("JASAGENT_SESSION"), "登录会话被转发");
        assertTrue(hit.cookie().contains("jaeger-ui=keep"));
        assertFalse(hit.authorization().contains(cookie), "鉴权头被转发");
        assertEquals("null", hit.forwarded());
        assertTrue(EVIL.isEmpty());
        assertFalse(page.getResponse().getHeaderValues(HttpHeaders.SET_COOKIE).stream()
                .anyMatch(value -> String.valueOf(value).toLowerCase().startsWith("jasagent_session=")), "上游覆盖了登录会话");
        assertTrue(page.getResponse().getHeaderValues(HttpHeaders.SET_COOKIE).stream()
                .anyMatch(value -> String.valueOf(value).startsWith("jaeger-ui=")));

        JAEGER.clear();
        MvcResult child = mvc.perform(get("/admin/jaeger/search").queryParam("url", "http://127.0.0.1:" + evil.getAddress().getPort() + "/steal")
                        .cookie(session(cookie)))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(child.getResponse().getContentAsString().contains("JAEGER-PAGE"));
        assertEquals("/admin/jaeger/search", JAEGER.get(0).path());
        assertTrue(JAEGER.get(0).host().endsWith(":" + jaeger.getAddress().getPort()), "上游主机被改写");
        assertTrue(String.valueOf(JAEGER.get(0).query()).contains("127.0.0.1"));
        assertFalse(WEB.contains("/admin/jaeger/search"));
        assertTrue(EVIL.isEmpty());
    }

    /**
     * 前缀外面的路径仍走控制台。编码和点段跳出前缀时两边都不出页面。
     */
    @Test
    void prefixCannotFallThroughToTheConsole() throws Exception {
        String cookie = login(ADMIN, ADMIN_SECRET);
        MvcResult extra = mvc.perform(get("/admin/jaegerextra").cookie(session(cookie))).andExpect(status().isOk()).andReturn();
        assertTrue(extra.getResponse().getContentAsString().contains("web-page"));
        assertTrue(JAEGER.isEmpty());
        for (String raw : new String[] {
                "/admin/jaeger/../../secret",
                "/admin/jaeger/%2e%2e/%2e%2e/secret",
                "/admin/jaeger%2f..%2f..%2fsecret",
                "/admin/jaeger%00"
        }) {
            JAEGER.clear();
            WEB.clear();
            MvcResult blocked = mvc.perform(get("/admin/").with(rawPath(raw)).cookie(session(cookie))).andReturn();
            assertEquals(403, blocked.getResponse().getStatus(), raw);
            assertFalse(blocked.getResponse().getContentAsString().contains("JAEGER-PAGE"), raw);
            assertFalse(blocked.getResponse().getContentAsString().contains("web-page"), raw);
            assertTrue(JAEGER.isEmpty(), raw);
            assertTrue(WEB.isEmpty(), raw);
        }
        JAEGER.clear();
        WEB.clear();
        MvcResult encoded = mvc.perform(get("/admin/").with(rawPath("/admin/jaeger%252fsearch")).cookie(session(cookie)))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(encoded.getResponse().getContentAsString().contains("JAEGER-PAGE"));
        assertEquals("/admin/jaeger/search", JAEGER.get(0).path());
        assertTrue(WEB.isEmpty());
        assertTrue(EVIL.isEmpty());
    }

    /**
     * 用原始路径覆盖 MockMvc 可能先折叠掉的请求 URI 和 servlet 路径。
     *
     * @param raw 原始路径
     * @return 请求后处理器
     */
    private static RequestPostProcessor rawPath(String raw) {
        return request -> {
            request.setRequestURI(raw);
            request.setServletPath(raw);
            request.setPathInfo(null);
            return request;
        };
    }

    /**
     * 登录并返回 Cookie 值。响应里不出现口令。
     *
     * @param login 登录名
     * @param secret 口令
     * @return Cookie 值
     */
    private String login(String login, String secret) throws Exception {
        MvcResult result = mvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=" + login + "&password=" + secret))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(secret), "登录响应含有口令");
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(header != null && header.startsWith("JASAGENT_SESSION="));
        int end = header.indexOf(';');
        return header.substring("JASAGENT_SESSION=".length(), end < 0 ? header.length() : end);
    }

    /**
     * 生成口令。只用字母和数字，避免表单编码改变它。
     *
     * @return 口令
     */
    private static String secret() {
        return "pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 包一枚会话 Cookie。
     *
     * @param value 令牌
     * @return Cookie
     */
    private static Cookie session(String value) {
        return new Cookie("JASAGENT_SESSION", value);
    }

    /**
     * Jaeger 桩看到的一次访问。值只用于比较。
     *
     * @param path 路径
     * @param host Host 头
     * @param cookie Cookie 头
     * @param authorization Authorization 头
     * @param forwarded X-Forwarded-Host
     * @param query 查询串
     */
    private record Hit(String path, String host, String cookie, String authorization, String forwarded, String query) {
    }
}
