package dev.jasagent.gateway.ingress;

import dev.jasagent.api.auth.AddUser;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.gateway.auth.GatewayLocalController;
import dev.jasagent.gateway.auth.GatewaySessionFilter;
import dev.jasagent.gateway.auth.LoginFormFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不启动整个 Spring 容器，直接检查转发头过滤器、会话 Cookie 和相对跳转。
 * 受信任网段是 10.42.0.0/16，用来模拟 Ingress 这一跳，不是生产默认值。
 */
class PublicIngressFilterTest {
    private static final String PASSWORD = IngressFixtures.PASSWORD;
    private static LocalAuthService auth;
    private static GatewayLocalController controller;
    private static GatewaySessionFilter sessionFilter;
    private static LoginFormFilter loginFilter;
    private static TrustedForwardedHeaderFilter forwarded;

    /**
     * 准备一个临时用户，以及按 10.42.0.0/16 信任上一跳的过滤器。
     */
    @BeforeAll
    static void users() throws Exception {
        Path home = Files.createTempDirectory("gw019-filter");
        auth = new LocalAuthService(home);
        auth.addUser(new AddUser(IngressFixtures.LOGIN, PASSWORD.toCharArray(), null, false)).toCompletableFuture().join();
        controller = new GatewayLocalController(auth);
        sessionFilter = new GatewaySessionFilter(auth);
        loginFilter = new LoginFormFilter();
        forwarded = new TrustedForwardedHeaderFilter(new TrustedProxyList("10.42.0.0/16"));
    }

    /**
     * 不受信任的客户端带上 X-Forwarded-* 时，协议保持 http，头被丢掉，Cookie 不加 Secure，跳转仍是相对路径。
     * X-Forwarded-For 里写一个受信任地址也不能把这一跳变成 Ingress。
     */
    @Test
    void untrustedClientCannotChangeSchemeCookieOrRedirect() throws Exception {
        MockHttpServletRequest request = loginRequest("203.0.113.8");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "evil.example");
        request.addHeader("X-Forwarded-For", "10.42.1.8");
        request.addHeader("Forwarded", "proto=https;host=evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        forwarded.doFilter(request, response, (req, res) -> {
            HttpServletRequest seen = (HttpServletRequest) req;
            assertEquals("http", seen.getScheme());
            assertFalse(seen.isSecure());
            assertNull(seen.getHeader("X-Forwarded-Proto"));
            assertNull(seen.getHeader("X-Forwarded-Host"));
            assertNull(seen.getHeader("X-Forwarded-For"));
            assertNull(seen.getHeader("Forwarded"));
            assertEquals(Boolean.TRUE, seen.getAttribute(TrustedForwardedHeaderFilter.APPLIED));
            loginFilter.doFilter(seen, res, (req2, res2) -> controller.login((HttpServletRequest) req2, (HttpServletResponse) res2));
        });
        SessionCookies.assertSession(response.getHeader(HttpHeaders.SET_COOKIE), false, false, PASSWORD);

        MockHttpServletResponse redirect = new MockHttpServletResponse();
        MockHttpServletRequest page = new MockHttpServletRequest("GET", "/admin/");
        page.setRequestURI("/admin/");
        page.setRemoteAddr("203.0.113.8");
        page.addHeader("X-Forwarded-Proto", "https");
        page.addHeader("X-Forwarded-Host", "evil.example");
        forwarded.doFilter(page, redirect, (req, res) -> sessionFilter.doFilter(req, res, (req2, res2) -> {
            throw new AssertionError("未登录页面不应继续");
        }));
        assertRelative(redirect, "/admin/login");
    }

    /**
     * 本机明文、没有转发头时不加 Secure。连接本身是 TLS 时仍然加，避免把直接 HTTPS 误伤。
     */
    @Test
    void plainHttpOmitsSecureAndDirectTlsKeepsIt() throws Exception {
        MockHttpServletResponse plain = postLogout("127.0.0.1", false, null);
        SessionCookies.assertSession(plain.getHeader(HttpHeaders.SET_COOKIE), false, true, PASSWORD);

        MockHttpServletRequest tls = logoutRequest("127.0.0.1");
        tls.setSecure(true);
        tls.setScheme("https");
        MockHttpServletResponse secure = new MockHttpServletResponse();
        forwarded.doFilter(tls, secure, (req, res) -> {
            assertEquals("https", ((HttpServletRequest) req).getScheme());
            assertTrue(((HttpServletRequest) req).isSecure());
            controller.logout((HttpServletRequest) req, (HttpServletResponse) res);
        });
        SessionCookies.assertSession(secure.getHeader(HttpHeaders.SET_COOKIE), true, true, PASSWORD);
    }

    /**
     * 受信任的上一跳声明 https 时，签发和清除都带 Secure，多次取值只认最右侧一段。
     * 主机可以改到请求视图里，但 Location 仍然是相对路径。
     */
    @Test
    void trustedHttpsSetsSecureOnIssueAndClear() throws Exception {
        MockHttpServletRequest request = loginRequest("10.42.1.8");
        request.addHeader("X-Forwarded-Proto", "http");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "client.example, ingress.internal");
        request.addHeader("X-Forwarded-Port", "443");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.9.9.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        forwarded.doFilter(request, response, (req, res) -> {
            HttpServletRequest seen = (HttpServletRequest) req;
            assertEquals("https", seen.getScheme());
            assertTrue(seen.isSecure());
            assertEquals("https", seen.getHeader("X-Forwarded-Proto"));
            assertEquals("ingress.internal", seen.getServerName());
            assertEquals("ingress.internal", seen.getHeader("X-Forwarded-Host"));
            assertEquals(443, seen.getServerPort());
            assertEquals("10.9.9.9", seen.getHeader("X-Forwarded-For"));
            assertTrue(seen.getRequestURL().toString().startsWith("https://ingress.internal"));
            loginFilter.doFilter(seen, res, (req2, res2) -> controller.login((HttpServletRequest) req2, (HttpServletResponse) res2));
        });
        SessionCookies.assertSession(response.getHeader(HttpHeaders.SET_COOKIE), true, false, PASSWORD);

        MockHttpServletResponse cleared = postLogout("10.42.1.8", false, "https");
        SessionCookies.assertSession(cleared.getHeader(HttpHeaders.SET_COOKIE), true, true, PASSWORD);

        MockHttpServletResponse redirect = new MockHttpServletResponse();
        MockHttpServletRequest page = new MockHttpServletRequest("GET", "/");
        page.setRequestURI("/admin/cluster");
        page.setRemoteAddr("10.42.8.8");
        page.addHeader("X-Forwarded-Proto", "https");
        page.addHeader("X-Forwarded-Host", "ingress.internal");
        forwarded.doFilter(page, redirect, (req, res) -> sessionFilter.doFilter(req, res, (req2, res2) -> {
            throw new AssertionError("未登录页面不应继续");
        }));
        assertRelative(redirect, "/admin/login");
        assertFalse(redirect.getHeader(HttpHeaders.LOCATION).contains("ingress.internal"));
    }

    /**
     * 受信任的上一跳如果声明的是 http，即使更前面写了 https，也不加 Secure，协议保持 http。
     */
    @Test
    void trustedHttpDoesNotForceSecure() throws Exception {
        MockHttpServletRequest request = loginRequest("10.42.1.8");
        request.setSecure(true);
        request.setScheme("https");
        request.addHeader("X-Forwarded-Proto", "https, http");
        MockHttpServletResponse response = new MockHttpServletResponse();
        forwarded.doFilter(request, response, (req, res) -> {
            HttpServletRequest seen = (HttpServletRequest) req;
            assertEquals("http", seen.getScheme());
            assertFalse(seen.isSecure());
            assertEquals("http", seen.getHeader("X-Forwarded-Proto"));
            loginFilter.doFilter(seen, res, (req2, res2) -> controller.login((HttpServletRequest) req2, (HttpServletResponse) res2));
        });
        SessionCookies.assertSession(response.getHeader(HttpHeaders.SET_COOKIE), false, false, PASSWORD);
    }

    /**
     * 只有 Forwarded 头、没有 X-Forwarded-Proto 时，不把请求改成 HTTPS。Ingress 必须自己写 X-Forwarded-Proto。
     */
    @Test
    void forwardedHeaderAloneDoesNotImplyHttps() throws Exception {
        MockHttpServletRequest request = logoutRequest("10.42.1.8");
        request.addHeader("Forwarded", "proto=https");
        MockHttpServletResponse response = new MockHttpServletResponse();
        forwarded.doFilter(request, response, (req, res) -> {
            HttpServletRequest seen = (HttpServletRequest) req;
            assertEquals("http", seen.getScheme());
            assertNull(seen.getHeader("Forwarded"));
            controller.logout(seen, (HttpServletResponse) res);
        });
        SessionCookies.assertSession(response.getHeader(HttpHeaders.SET_COOKIE), false, true, PASSWORD);
    }

    /**
     * 站点根和旧登录地址的 Location 是写死的相对路径，不读取转发头。
     */
    @Test
    void controllerRedirectsStayRelative() {
        assertEquals("/admin/", controller.root().getHeaders().getFirst(HttpHeaders.LOCATION));
        assertEquals("/admin/login", controller.loginPage().getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    /**
     * 登录请求。远程地址由调用方指定，正文里是测试用户。
     *
     * @param remote 套接字对端
     * @return 尚未经过过滤器的请求
     */
    private static MockHttpServletRequest loginRequest(String remote) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRequestURI("/login");
        request.setRemoteAddr(remote);
        request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        request.setContent(("user=" + IngressFixtures.LOGIN + "&password=" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /**
     * 退出请求。不带会话也能清 Cookie。
     *
     * @param remote 套接字对端
     * @return 尚未经过过滤器的请求
     */
    private static MockHttpServletRequest logoutRequest(String remote) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        request.setRequestURI("/logout");
        request.setRemoteAddr(remote);
        return request;
    }

    /**
     * 经转发头过滤器调用退出。
     *
     * @param remote 套接字对端
     * @param secure 连接本身是否是 TLS
     * @param proto 要附加的 X-Forwarded-Proto；没有时为空
     * @return 响应
     */
    private static MockHttpServletResponse postLogout(String remote, boolean secure, String proto) throws ServletException, IOException {
        MockHttpServletRequest request = logoutRequest(remote);
        request.setSecure(secure);
        if (proto != null) {
            request.addHeader("X-Forwarded-Proto", proto);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        forwarded.doFilter(request, response, (req, res) -> controller.logout((HttpServletRequest) req, (HttpServletResponse) res));
        return response;
    }

    /**
     * Location 必须正好是这条相对路径，不能带协议或主机。
     *
     * @param response 跳转响应
     * @param location 期望的相对路径
     */
    private static void assertRelative(MockHttpServletResponse response, String location) {
        assertEquals(302, response.getStatus());
        assertEquals(location, response.getHeader(HttpHeaders.LOCATION));
        assertFalse(response.getHeader(HttpHeaders.LOCATION).contains("http://"));
        assertFalse(response.getHeader(HttpHeaders.LOCATION).contains("https://"));
        assertFalse(response.getHeader(HttpHeaders.LOCATION).contains("127.0.0.1"));
    }
}
