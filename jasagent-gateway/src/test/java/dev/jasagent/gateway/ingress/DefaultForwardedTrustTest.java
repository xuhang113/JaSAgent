package dev.jasagent.gateway.ingress;

import dev.jasagent.gateway.proxy.WebConsoleProxy;
import dev.jasagent.it.UnsetTokenConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运行中的网关使用默认信任名单。回环上的客户端自己带的转发头不能改协议、Cookie 或跳转。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({UnsetTokenConfiguration.class, ForwardedObservationConfiguration.class})
class DefaultForwardedTrustTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private TrustedProxyList trusted;
    @Autowired
    private WebConsoleProxy web;
    @Autowired
    private Environment environment;

    /**
     * 准备临时用户和仓库外口令文件。不覆盖转发头名单，让它保持配置里的默认空白。
     *
     * @param registry 测试属性
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        IngressFixtures.register(registry);
    }

    /**
     * 注入的名单不信任回环和集群网段。上游仍是本机 web，容器转发策略是 none，副本数是 1。
     */
    @Test
    void shippedDefaultsTrustNobodyAndKeepLocalWeb() {
        assertFalse(trusted.trusts("127.0.0.1"));
        assertFalse(trusted.trusts("::1"));
        assertFalse(trusted.trusts("10.42.1.8"));
        assertFalse(trusted.https("127.0.0.1", "https"));
        assertEquals("http://127.0.0.1:18722", web.origin().toString());
        assertEquals("http://127.0.0.1:18722", environment.getProperty("jasagent.gateway.web-origin"));
        assertEquals("none", environment.getProperty("server.forward-headers-strategy"));
        assertEquals("true", environment.getProperty("server.tomcat.use-relative-redirects"));
        assertEquals("1", environment.getProperty("jasagent.gateway.replicas"));
        String configured = environment.getProperty(TrustedProxyList.PROPERTY);
        assertTrue(configured == null || configured.isBlank());
    }

    /**
     * 从 127.0.0.1 伪造 HTTPS、主机和转发地址时，过滤器会丢掉这些头。登录 Cookie 不加 Secure，跳转保持相对路径。
     */
    @Test
    void loopbackSpoofDoesNotChangeCookieRedirectOrScheme() throws Exception {
        MvcResult login = mvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=" + IngressFixtures.LOGIN + "&password=" + IngressFixtures.PASSWORD)
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-For", "10.42.1.8"))
                .andExpect(status().isOk())
                .andReturn();
        SessionCookies.assertSession(login.getResponse().getHeader(HttpHeaders.SET_COOKIE), false, false, IngressFixtures.PASSWORD);
        assertObserved(login, "http", "false", "");

        MvcResult root = mvc.perform(get("/")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "evil.example"))
                .andExpect(status().isFound())
                .andReturn();
        assertEquals("/admin/", root.getResponse().getHeader(HttpHeaders.LOCATION));
        assertObserved(root, "http", "false", "");

        MvcResult loginPage = mvc.perform(get("/login")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "evil.example"))
                .andExpect(status().isFound())
                .andReturn();
        assertEquals("/admin/login", loginPage.getResponse().getHeader(HttpHeaders.LOCATION));

        MvcResult console = mvc.perform(get("/admin/")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "evil.example"))
                .andExpect(status().isFound())
                .andReturn();
        assertEquals("/admin/login", console.getResponse().getHeader(HttpHeaders.LOCATION));
        assertFalse(console.getResponse().getHeader(HttpHeaders.LOCATION).contains("http://"));
        assertFalse(console.getResponse().getHeader(HttpHeaders.LOCATION).contains("evil.example"));

        MvcResult logout = mvc.perform(post("/logout")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isNoContent())
                .andReturn();
        SessionCookies.assertSession(logout.getResponse().getHeader(HttpHeaders.SET_COOKIE), false, true, IngressFixtures.PASSWORD);
    }

    /**
     * 没有转发头的明文登录同样不加 Secure。
     */
    @Test
    void plainLoopbackLoginOmitsSecure() throws Exception {
        MvcResult login = mvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=" + IngressFixtures.LOGIN + "&password=" + IngressFixtures.PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        SessionCookies.assertSession(login.getResponse().getHeader(HttpHeaders.SET_COOKIE), false, false, IngressFixtures.PASSWORD);
        assertObserved(login, "http", "false", "");
    }

    /**
     * 观察头应说明过滤器已经跑过，并且协议没有被转发头改掉。
     *
     * @param result 响应
     * @param scheme 期望协议
     * @param secure 期望是否安全
     * @param proto 期望还能看见的 X-Forwarded-Proto；被丢掉时为空串
     */
    private static void assertObserved(MvcResult result, String scheme, String secure, String proto) {
        assertEquals("true", result.getResponse().getHeader(ForwardedObservationConfiguration.ForwardedObservationFilter.APPLIED));
        assertEquals(scheme, result.getResponse().getHeader(ForwardedObservationConfiguration.ForwardedObservationFilter.SCHEME));
        assertEquals(secure, result.getResponse().getHeader(ForwardedObservationConfiguration.ForwardedObservationFilter.SECURE));
        assertEquals(proto, result.getResponse().getHeader(ForwardedObservationConfiguration.ForwardedObservationFilter.PROTO));
    }
}
