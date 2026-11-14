package dev.jasagent.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.auth.LocalAuthService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import dev.jasagent.it.UnsetTokenConfiguration;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制台改为反代，未登录只留健康检查、登录和登录页。上游关掉后不能再出现内嵌页面。
 */
@SpringBootTest(properties = "jasagent.gateway.console-proxy-test=true")
@AutoConfigureMockMvc
@Import(UnsetTokenConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsoleProxyTest {
    private static final String USER = "proxy-user";
    private static final String PASSWORD = "test-password-1";
    private static final String EMBEDDED_PAGE = "index-BA871a2a.js";
    private static Path home;
    private static HttpServer upstream;
    private static final AtomicBoolean upstreamOpen = new AtomicBoolean(true);

    @Autowired
    private MockMvc mvc;

    /**
     * 准备临时用户目录和控制台桩。桩的正文带 upstream: 前缀，用来确认响应来自上游。
     * 属性注册发生在测试方法之前，所以桩必须在这里先听上。
     */
    private static void ensureUpstream() throws Exception {
        if (upstream != null) {
            return;
        }
        home = Files.createTempDirectory("gw015-console");
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = ("upstream:" + path).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        new LocalAuthService(home).addUser(new AddUser(USER, PASSWORD.toCharArray(), null, false)).toCompletableFuture().join();
    }

    /**
     * 把网关指到桩上游，并清掉进程里的内部令牌，避免这次上下文把请求发到 agent。
     *
     * @param registry 测试属性
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        ensureUpstream();
        registry.add("jasagent.home", () -> home.toString());
        registry.add("jasagent.gateway.web-origin", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
        registry.add("jasagent.gateway.links.enabled", () -> "false");
    }

    /**
     * 关掉控制台桩。
     */
    @AfterAll
    static void stopUpstream() {
        if (upstreamOpen.compareAndSet(true, false) && upstream != null) {
            upstream.stop(0);
        }
    }

    /**
     * 未登录访问健康检查得到 up。
     */
    @Test
    @Order(1)
    void healthIsUpWithoutLogin() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"up\"")));
    }

    /**
     * 根路径和旧登录地址仍分别转到控制台入口和登录页。POST /login 仍是登录接口。
     */
    @Test
    @Order(2)
    void rootAndLoginKeepTheirTargets() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/admin/"));
        mvc.perform(get("/login"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/admin/login"));
        MvcResult rejected = mvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=gateway-nobody&password=not-a-real-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("\"errorCode\":\"AUTH\"")))
                .andReturn();
        assertFalse(rejected.getResponse().getContentAsString().contains("not-a-real-password"));
    }

    /**
     * 正确登录名和口令返回 200，并写下 JASAGENT_SESSION。响应、响应头和日志都不出现口令原文。
     */
    @Test
    @Order(3)
    void loginSetsSessionCookieWithoutEchoingPassword() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            MvcResult result = mvc.perform(post("/login")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("user=" + USER + "&password=" + PASSWORD))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("JASAGENT_SESSION=")))
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            assertFalse(body.contains(PASSWORD), "响应正文包含口令");
            assertFalse(String.valueOf(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains(PASSWORD), "响应头包含口令");
            for (ILoggingEvent event : logs.list) {
                assertFalse(String.valueOf(event.getFormattedMessage()).contains(PASSWORD), "日志包含口令");
            }
        } finally {
            root.detachAppender(logs);
        }
    }

    /**
     * 未登录访问要转发的 /api、当前用户和集群都是 401，errorCode 为 AUTH。
     */
    @Test
    @Order(4)
    void apisRejectAnonymousCalls() throws Exception {
        assertAuth(mvc.perform(get("/api/sessions")).andReturn());
        assertAuth(mvc.perform(get("/api/me")).andReturn());
        assertAuth(mvc.perform(get("/api/cluster")).andReturn());
    }

    /**
     * 未登录可以打开登录页，也可以读取登录页用的静态资源。
     */
    @Test
    @Order(5)
    void loginPageAndAssetsDoNotNeedASession() throws Exception {
        mvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("upstream:/admin/login")));
        mvc.perform(get("/admin/assets/login.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("upstream:/admin/assets/login.js")));
    }

    /**
     * 未登录访问控制台入口时转到登录页，不返回上游的业务正文，也不返回已删除的内嵌页面。
     */
    @Test
    @Order(6)
    void consoleRequiresLogin() throws Exception {
        MvcResult result = mvc.perform(get("/admin/"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.endsWith("/admin/login")))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("upstream:/admin/"));
        assertFalse(body.contains(EMBEDDED_PAGE));
        assertFalse(body.contains("gateway-admin"));
    }

    /**
     * 已登录访问控制台时，正文来自配置的上游。
     */
    @Test
    @Order(7)
    void signedInConsoleComesFromTheUpstream() throws Exception {
        String cookie = loginCookie();
        mvc.perform(get("/admin/").cookie(cookie(cookie)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("upstream:/admin/")))
                .andExpect(content().string(not(containsString(EMBEDDED_PAGE))));
    }

    /**
     * 上游关掉后，已登录的控制台请求返回错误，且不出现已删除的内嵌页面。
     */
    @Test
    @Order(8)
    void closedUpstreamDoesNotFallBackToEmbeddedPage() throws Exception {
        String cookie = loginCookie();
        if (upstreamOpen.compareAndSet(true, false)) {
            upstream.stop(0);
        }
        MvcResult result = mvc.perform(get("/admin/").cookie(cookie(cookie)))
                .andExpect(status().isBadGateway())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"errorCode\":\"UPSTREAM\""));
        assertFalse(body.contains(EMBEDDED_PAGE));
        assertFalse(body.contains("gateway-admin"));
        assertFalse(body.contains("upstream:/admin/"));
    }

    /**
     * 未配置内部令牌时，已登录的可转发请求不会发到 agent，正文也不含令牌原文。
     */
    @Test
    @Order(9)
    void missingInternalTokenDoesNotCallAnAgent() throws Exception {
        String cookie = loginCookie();
        MvcResult result = mvc.perform(get("/api/sessions").cookie(cookie(cookie)))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"errorCode\":\"INTERNAL\""));
        assertFalse(body.contains("gateway-it-token"));
        assertFalse(body.contains(PASSWORD));
    }

    /**
     * 401 正文使用 AUTH，并且不夹带口令。
     *
     * @param result 未登录请求的结果
     */
    private static void assertAuth(MvcResult result) throws Exception {
        assertTrue(result.getResponse().getStatus() == 401, result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentAsString().contains("\"errorCode\":\"AUTH\""));
        assertFalse(result.getResponse().getContentAsString().contains(PASSWORD));
    }

    /**
     * 用正确口令登录，取出会话 Cookie 的值。
     *
     * @return Cookie 值
     */
    private String loginCookie() throws Exception {
        MvcResult result = mvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=" + USER + "&password=" + PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(header != null && header.startsWith("JASAGENT_SESSION="));
        String value = header.substring("JASAGENT_SESSION=".length());
        int end = value.indexOf(';');
        if (end >= 0) {
            value = value.substring(0, end);
        }
        assertFalse(value.contains(PASSWORD));
        return value;
    }

    /**
     * 做成请求要带的会话 Cookie。
     *
     * @param value Cookie 值
     * @return 会话 Cookie
     */
    private static jakarta.servlet.http.Cookie cookie(String value) {
        return new jakarta.servlet.http.Cookie("JASAGENT_SESSION", value);
    }
}
