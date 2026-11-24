package dev.jasagent.gateway.jaeger;

import com.sun.net.httpserver.HttpServer;
import dev.jasagent.auth.AccountProfile;
import dev.jasagent.auth.AccountRoles;
import dev.jasagent.gateway.auth.GatewaySessionFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jaeger 前缀不能绕过，上游地址不能被请求改写，登录会话也不能跟着转发出去。
 */
class JaegerPathsTest {
    /**
     * 编码、大小写、点段和空字节都算进前缀。名字只是多出来的字符不算。
     */
    @Test
    void prefixCoversEncodingsAndStopsAtTheBoundary() {
        assertTrue(JaegerPaths.matches("/admin/jaeger"));
        assertTrue(JaegerPaths.matches("/admin/jaeger/"));
        assertTrue(JaegerPaths.matches("/admin/jaeger/search"));
        assertTrue(JaegerPaths.matches("/admin/jaeger/../jaeger/search"));
        assertTrue(JaegerPaths.matches("/admin/jaeger/%2e%2e/secret"));
        assertTrue(JaegerPaths.matches("/admin/jaeger%2fsearch"));
        assertTrue(JaegerPaths.matches("/admin//jaeger"));
        assertTrue(JaegerPaths.matches("/admin/./jaeger"));
        assertTrue(JaegerPaths.matches("/Admin/Jaeger"));
        assertTrue(JaegerPaths.matches("/admin/jaeger;foo=bar"));
        assertTrue(JaegerPaths.matches("/admin/jaeger%252fsearch"));
        assertTrue(JaegerPaths.matches("/admin\\jaeger"));
        assertTrue(JaegerPaths.matches("/admin/jaeger%c0%afsearch"));
        assertTrue(JaegerPaths.matches("/admin/jaeger%00"));
        assertFalse(JaegerPaths.matches("/admin/jaegerextra"));
        assertFalse(JaegerPaths.matches("/admin/jaeger.evil"));
        assertFalse(JaegerPaths.matches("/admin/cluster"));
        assertFalse(JaegerPaths.matches("/admin/login"));
        assertEquals("/admin/jaeger/search", JaegerPaths.upstreamPath("/admin/jaeger/search").orElseThrow());
        assertEquals("/admin/jaeger/search", JaegerPaths.upstreamPath("/admin/jaeger/foo/../search").orElseThrow());
        assertEquals(Optional.empty(), JaegerPaths.upstreamPath("/admin/jaeger/../../secret"));
        assertEquals(Optional.empty(), JaegerPaths.upstreamPath("/admin/jaeger%00"));
    }

    /**
     * 带用户信息的上游被拒绝，异常文本不含那段用户信息。空白配置表示先不反代。
     */
    @Test
    void upstreamComesOnlyFromConfiguration() {
        String marker = "m-" + UUID.randomUUID();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> JaegerUpstream.parse("http://user:" + marker + "@127.0.0.1:9"));
        assertFalse(String.valueOf(failure.getMessage()).contains(marker), "异常含有用户信息");
        assertFalse(JaegerUpstream.parse("  ").enabled());
        JaegerUpstream upstream = JaegerUpstream.parse("http://127.0.0.1:16686");
        URI target = upstream.target("/admin/jaeger/search", "url=http://127.0.0.1:9/steal");
        assertEquals("127.0.0.1", target.getHost());
        assertEquals(16686, target.getPort());
        assertEquals("/admin/jaeger/search", target.getPath());
        assertFalse(target.toString().startsWith("http://127.0.0.1:9"), target.getHost());
        assertThrows(IllegalStateException.class, () -> upstream.target("/secret", null));
    }

    /**
     * 两个过滤器实例都只打向自己构造时的上游。请求头、查询串和另一台监听都改不了主机。
     * 登录会话 Cookie 不转发，上游写回的同名 Set-Cookie 也不覆盖。
     * 副作用：在回环上打开 Jaeger 桩和另一台不该被访问的桩。
     */
    @Test
    void proxyKeepsUpstreamCookiesAndPrefix() throws Exception {
        List<Hit> hits = new CopyOnWriteArrayList<>();
        AtomicInteger evil = new AtomicInteger();
        HttpServer jaeger = server(hits, false);
        HttpServer other = server(null, true);
        other.createContext("/", exchange -> {
            evil.incrementAndGet();
            byte[] body = "EVIL".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        other.start();
        jaeger.start();
        try {
            assertNoMutableStatic(JaegerPaths.class);
            assertNoMutableStatic(JaegerUpstream.class);
            assertNoMutableStatic(JaegerProxyFilter.class);
            JaegerProxyFilter first = new JaegerProxyFilter("http://127.0.0.1:" + jaeger.getAddress().getPort());
            JaegerProxyFilter second = new JaegerProxyFilter("http://127.0.0.1:" + jaeger.getAddress().getPort());
            AccountProfile admin = new AccountProfile("ops", AccountRoles.ADMIN, "tenant-ops");
            AccountProfile user = new AccountProfile("ada", AccountRoles.USER, "tenant-a");
            String token = "session-" + UUID.randomUUID();

            MockHttpServletResponse anonymous = exchange(first, "/admin/jaeger", null, null, null);
            assertEquals(302, anonymous.getStatus());
            assertEquals("/admin/login", anonymous.getHeader("Location"));
            assertFalse(anonymous.getContentAsString().contains("JAEGER-PAGE"));
            assertEquals(0, hits.size());

            MockHttpServletResponse forbidden = exchange(first, "/admin/jaeger/search", user, token, null);
            assertEquals(403, forbidden.getStatus());
            assertTrue(forbidden.getContentAsString().contains("\"errorCode\":\"FORBIDDEN\""));
            assertFalse(forbidden.getContentAsString().contains("JAEGER-PAGE"));
            assertFalse(forbidden.getContentAsString().contains(token), "拒绝正文含有会话");
            assertEquals(0, hits.size());

            MockHttpServletResponse escaped = exchange(first, "/admin/jaeger/../../secret", admin, token, null);
            assertEquals(403, escaped.getStatus());
            assertEquals(0, hits.size());
            assertEquals(0, evil.get());

            MockHttpServletResponse page = exchange(first, "/admin/jaeger/search", admin, token, "url=http://127.0.0.1:" + other.getAddress().getPort() + "/steal");
            assertEquals(200, page.getStatus());
            assertTrue(page.getContentAsString().contains("JAEGER-PAGE"));
            assertEquals(1, hits.size());
            Hit hit = hits.get(0);
            assertEquals("/admin/jaeger/search", hit.path());
            assertEquals("127.0.0.1", hit.host().split(":")[0]);
            assertTrue(hit.host().endsWith(":" + jaeger.getAddress().getPort()), hit.host());
            assertFalse(String.valueOf(hit.cookie()).contains(token), "登录会话被转发");
            assertFalse(String.valueOf(hit.cookie()).contains("JASAGENT_SESSION"), "登录会话被转发");
            assertTrue(String.valueOf(hit.cookie()).contains("jaeger-ui=keep"));
            assertEquals(null, hit.forwarded());
            assertFalse(String.valueOf(hit.authorization()).contains(token), "鉴权头被转发");
            assertEquals(0, evil.get());
            assertFalse(String.valueOf(page.getHeader("Set-Cookie")).contains("JASAGENT_SESSION"), "上游覆盖了登录会话");
            assertTrue(page.getHeaders("Set-Cookie").stream().anyMatch(value -> value.startsWith("jaeger-ui=")));
            assertFalse(page.getHeaders("Location").stream().anyMatch(value -> value.contains("evil.example")));
            assertTrue(page.getHeaders("Location").contains("/admin/jaeger/search"));

            MockHttpServletResponse outside = exchange(second, "/admin/jaegerextra", admin, token, null);
            assertEquals(200, outside.getStatus());
            assertFalse(outside.getContentAsString().contains("JAEGER-PAGE"));
            assertEquals(1, hits.size());

            hits.clear();
            MockHttpServletResponse again = exchange(second, "/admin/jaeger", admin, token, null);
            assertEquals(200, again.getStatus());
            assertEquals(1, hits.size());
            assertEquals(0, evil.get());

            JaegerProxyFilter disabled = new JaegerProxyFilter(" ");
            MockHttpServletResponse down = exchange(disabled, "/admin/jaeger", admin, token, null);
            assertEquals(502, down.getStatus());
            assertTrue(down.getContentAsString().contains("\"errorCode\":\"UPSTREAM\""));
            assertFalse(down.getContentAsString().contains("JAEGER-PAGE"));
            assertFalse(down.getContentAsString().contains(token), "不可用正文含有会话");
        } finally {
            jaeger.stop(0);
            other.stop(0);
        }
    }

    /**
     * 打开一台只记录访问的桩。evil 为真时先不注册路径，交给调用方。
     *
     * @param hits 要记下的访问；不记录时为空
     * @param bare 为真时不注册根路径
     * @return 尚未启动的服务
     */
    private static HttpServer server(List<Hit> hits, boolean bare) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        if (!bare) {
            server.createContext("/", exchange -> {
                if (hits != null) {
                    hits.add(new Hit(
                            exchange.getRequestURI().getPath(),
                            exchange.getRequestHeaders().getFirst("Host"),
                            exchange.getRequestHeaders().getFirst("Cookie"),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            exchange.getRequestHeaders().getFirst("X-Forwarded-Host")));
                }
                byte[] body = "JAEGER-PAGE".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
                exchange.getResponseHeaders().add("Set-Cookie", "JASAGENT_SESSION=from-upstream");
                exchange.getResponseHeaders().add("Set-Cookie", "jaeger-ui=keep-ui");
                exchange.getResponseHeaders().add("Location", "https://evil.example/steal");
                exchange.getResponseHeaders().add("Location", "/admin/jaeger/search");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
        }
        return server;
    }

    /**
     * 走一次过滤器。继续往后时把状态写成 200。
     *
     * @param filter 被测过滤器
     * @param path 原始路径
     * @param account 已登录账号；未登录时为空
     * @param token 会话值，只放在 Cookie 和 Authorization 里
     * @param query 查询串
     * @return 响应
     */
    private static MockHttpServletResponse exchange(JaegerProxyFilter filter, String path, AccountProfile account, String token, String query)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        request.setServletPath(path);
        if (query != null) {
            request.setQueryString(query);
        }
        request.addHeader("X-Forwarded-Host", "evil.example");
        request.addHeader("X-Jaeger-Upstream", "http://evil.example");
        request.addHeader("Host", "evil.example");
        if (token != null) {
            request.setCookies(new Cookie("JASAGENT_SESSION", token), new Cookie("jaeger-ui", "keep"));
            request.addHeader("Authorization", "Bearer " + token);
        }
        if (account != null) {
            request.setAttribute(GatewaySessionFilter.ACCOUNT, account);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger continued = new AtomicInteger();
        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            continued.incrementAndGet();
            ((jakarta.servlet.http.HttpServletResponse) servletResponse).setStatus(200);
        });
        if (JaegerPaths.matches(path)) {
            assertEquals(0, continued.get(), path);
        }
        return response;
    }

    /**
     * 这些类型不能靠可变静态字段在进程里记登录或上游。
     *
     * @param type 要检查的类型
     */
    private static void assertNoMutableStatic(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                throw new AssertionError(type.getSimpleName());
            }
        }
    }

    /**
     * 桩看到的一次访问。头的值只用于比较，不写进失败说明。
     *
     * @param path 路径
     * @param host Host 头
     * @param cookie Cookie 头
     * @param authorization Authorization 头
     * @param forwarded X-Forwarded-Host
     */
    private record Hit(String path, String host, String cookie, String authorization, String forwarded) {
    }
}
