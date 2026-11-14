package dev.jasagent.gateway.proxy;

import dev.jasagent.api.auth.Session;
import dev.jasagent.gateway.agent.InMemoryAgentLiveness;
import dev.jasagent.gateway.auth.GatewaySessionFilter;
import dev.jasagent.gateway.route.StickyAgentRouter;
import dev.jasagent.gateway.store.AffinityStore;
import dev.jasagent.gateway.token.InternalToken;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 未配置内部令牌时，已登录请求停在网关，不会交给 agent。
 */
class StickyProxyTokenTest {
    /**
     * 令牌为空时转发函数不会被调用，状态是 503。
     */
    @Test
    void unconfiguredTokenDoesNotForward() throws Exception {
        AffinityStore store = new AffinityStore() {
            @Override
            public Optional<String> find(String tenantId, String userId) {
                return Optional.empty();
            }

            @Override
            public void save(String tenantId, String userId, String instanceId) {
            }

            @Override
            public List<AffinityRow> list(String tenantId) {
                return List.of();
            }
        };
        StickyProxy proxy = new StickyProxy(
                new StickyAgentRouter(List.of(), new InMemoryAgentLiveness(), store),
                InternalToken.of(""),
                "local",
                null);
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/api/sessions");
        servlet.setRequestURI("/api/sessions");
        servlet.setAttribute(GatewaySessionFilter.SESSION, new Session("login-session", "cookie-token-value", "ada", null, Instant.now()));
        ServerRequest request = request(servlet);
        AtomicBoolean called = new AtomicBoolean();
        ServerResponse response = proxy.forward(request, ignored -> {
            called.set(true);
            return ServerResponse.ok().build();
        });
        assertFalse(called.get());
        assertEquals(503, response.statusCode().value());
        assertFalse(response.toString().contains("cookie-token-value"));
    }

    /**
     * 用 Spring 的 Servlet 请求包装出网关过滤器能读到的请求。这个类型没有公开构造器。
     *
     * @param servlet 带会话属性的请求
     * @return 函数式请求
     */
    private static ServerRequest request(MockHttpServletRequest servlet) throws Exception {
        Class<?> type = Class.forName("org.springframework.web.servlet.function.DefaultServerRequest");
        Constructor<?> constructor = type.getDeclaredConstructor(jakarta.servlet.http.HttpServletRequest.class, List.class);
        constructor.setAccessible(true);
        return (ServerRequest) constructor.newInstance(servlet, List.of(new MappingJackson2HttpMessageConverter()));
    }
}
