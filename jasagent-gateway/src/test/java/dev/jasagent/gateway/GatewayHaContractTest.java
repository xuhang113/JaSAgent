package dev.jasagent.gateway;

import dev.jasagent.auth.AccountProfile;
import dev.jasagent.auth.AccountRoles;
import dev.jasagent.gateway.agent.InMemoryAgentLiveness;
import dev.jasagent.gateway.auth.GatewaySessionFilter;
import dev.jasagent.gateway.cluster.ClusterAccessFilter;
import dev.jasagent.gateway.ingress.TrustedProxyList;
import dev.jasagent.gateway.proxy.StickyProxy;
import dev.jasagent.gateway.route.GatewayPaths;
import dev.jasagent.gateway.route.StickyAgentRouter;
import dev.jasagent.gateway.store.AffinityStore;
import dev.jasagent.gateway.token.InternalToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.ServerRequest;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多副本前必须守住的合同：事件流不整段读入内存，转发头不信任全网段，非 admin 进不了集群接口。
 * 网关主源码不再把存活探测写成只在本进程。
 */
class GatewayHaContractTest {
    /**
     * 旧的单副本句子。只在这个测试里出现，用来确认主源码已经改掉。
     */
    private static final String OUTDATED = "登录会话和存活探测都在网关进程内，本轮网关按单副本验收。";

    /**
     * 事件流走原样转发。不改写执行详情，也不把响应收成字符串。
     */
    @Test
    void eventStreamIsForwardedWithoutReadingTheBody() throws Exception {
        String path = "/api/executions/exec-1/events";
        assertTrue(GatewayPaths.proxied(HttpMethod.GET, path));
        assertFalse(GatewayPaths.execution(HttpMethod.GET, path));
        assertFalse(GatewayPaths.decorated(HttpMethod.GET, path));
        assertFalse(GatewayPaths.local(HttpMethod.GET, path));
        StickyProxy proxy = new StickyProxy(
                new StickyAgentRouter(() -> List.of(), new InMemoryAgentLiveness(), emptyStore()),
                InternalToken.of(""),
                "local",
                null);
        ServerRequest request = request(new MockHttpServletRequest("GET", path));
        assertTrue(proxy.plain(request));
        assertFalse(proxy.decorated(request));
        assertFalse(proxy.creates(request));
        String routes = Files.readString(Path.of("src/main/java/dev/jasagent/gateway/proxy/GatewayProxyRoutes.java"));
        int plain = routes.indexOf("\"agent-proxy\"");
        assertTrue(plain > 0);
        String tail = routes.substring(plain);
        assertFalse(tail.contains("modifyResponseBody"));
        assertFalse(tail.contains(".after("));
        assertFalse(tail.contains("readAllBytes"));
        String sticky = Files.readString(Path.of("src/main/java/dev/jasagent/gateway/proxy/StickyProxy.java"));
        assertFalse(sticky.contains("readAllBytes"));
        assertFalse(sticky.contains("SseEmitter"));
        String paths = Files.readString(Path.of("src/main/java/dev/jasagent/gateway/route/GatewayPaths.java"));
        assertTrue(paths.contains("事件流不在这里改写，避免把长连接响应整段读进内存"));
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java"))) {
            for (Path file : walk.filter(item -> item.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                assertFalse(text.contains("SseEmitter"), file.toString());
                assertFalse(text.contains("new EventSource"), file.toString());
            }
        }
    }

    /**
     * 信任名单拒绝 0.0.0.0/0 和 ::/0。客户端自己带的协议头，在不是 Ingress 这一跳时不被采用。
     */
    @Test
    void forwardedTrustRejectsTheWholeInternet() throws Exception {
        assertEquals("受信任转发地址不能覆盖全部地址",
                assertThrows(IllegalStateException.class, () -> new TrustedProxyList("0.0.0.0/0")).getMessage());
        assertEquals("受信任转发地址不能覆盖全部地址",
                assertThrows(IllegalStateException.class, () -> new TrustedProxyList("::/0")).getMessage());
        TrustedProxyList ingress = new TrustedProxyList("10.42.0.8");
        assertFalse(ingress.trusts("203.0.113.9"));
        assertFalse(ingress.https("203.0.113.9", "https"));
        assertEquals("", ingress.declaredScheme("203.0.113.9", "https"));
        assertTrue(ingress.https("10.42.0.8", "http, https"));
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        boolean seen = false;
        for (String line : props.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("jasagent.gateway.forwarded.trusted=")) {
                seen = true;
                assertFalse(trimmed.contains("0.0.0.0/0"));
                assertFalse(trimmed.contains("::/0"));
            }
        }
        assertTrue(seen);
        assertTrue(props.contains("jasagent.gateway.forwarded.trusted=${JASAGENT_GATEWAY_FORWARDED_TRUSTED:}"));
    }

    /**
     * 非 admin 到不了集群接口和集群页。其它接口不因这条规则被拦住。拒绝正文没有集群清单。
     */
    @Test
    void nonAdminCannotReachClusterApiOrPage() throws Exception {
        ClusterAccessFilter filter = new ClusterAccessFilter();
        AccountProfile user = new AccountProfile("ada", AccountRoles.USER, "tenant-a");
        AccountProfile admin = new AccountProfile("ops", AccountRoles.ADMIN, "tenant-ops");
        assertEquals(403, filtered(filter, "GET", "/api/cluster", user).getStatus());
        MockHttpServletResponse page = filtered(filter, "GET", "/admin/cluster", user);
        assertEquals(403, page.getStatus());
        assertFalse(page.getContentAsString().contains("agents"));
        assertFalse(page.getContentAsString().contains("tenant-ops"));
        assertEquals(200, filtered(filter, "GET", "/api/cluster", admin).getStatus());
        assertEquals(200, filtered(filter, "GET", "/api/sessions/other-user", user).getStatus());
    }

    /**
     * 生产装配用粘性库那一套 JDBC 打开共享存活，选路参数是这份存活，而不是新的进程内表。
     */
    @Test
    void productionWiringUsesSharedLiveness() throws Exception {
        String config = Files.readString(Path.of("src/main/java/dev/jasagent/gateway/config/GatewayConfiguration.java"));
        int start = config.indexOf("public MysqlAgentLiveness agentLiveness(");
        int end = config.indexOf("public MysqlAffinityStore affinityStore(");
        assertTrue(start > 0 && end > start);
        String method = config.substring(start, end);
        assertTrue(method.contains("MysqlAgentLiveness.open("));
        assertTrue(method.contains("MysqlAffinityStore.URL_PROPERTY"));
        assertTrue(method.contains("MysqlAffinityStore.USER_PROPERTY"));
        assertTrue(method.contains("MysqlAffinityStore.PASSWORD_FILE_PROPERTY"));
        assertTrue(config.contains("StickyAgentRouter(agentDiscovery, agentLiveness, affinityStore)"));
        assertTrue(config.contains("ClusterInventory(agentDiscovery, agentLiveness, affinityStore)"));
        assertFalse(config.contains("new InMemoryAgentLiveness("));
        assertTrue(config.contains(GatewayRuntime.SINGLE_REPLICA));
        try (Stream<Path> walk = Files.walk(Path.of("src/main"))) {
            for (Path file : walk.filter(item -> {
                String name = item.toString();
                return name.endsWith(".java") || name.endsWith(".properties");
            }).toList()) {
                assertFalse(Files.readString(file).contains(OUTDATED), file.toString());
            }
        }
    }

    /**
     * 跑一次过滤器。继续往后走时把状态写成 200，方便和 403 区分。
     *
     * @param filter 集群准入
     * @param method 方法
     * @param path 路径
     * @param account 已经登录的账号；为空表示过滤器里看不到账号
     * @return 响应
     */
    private static MockHttpServletResponse filtered(ClusterAccessFilter filter, String method, String path, AccountProfile account)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        if (account != null) {
            request.setAttribute(GatewaySessionFilter.ACCOUNT, account);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();
        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            continued.set(true);
            ((HttpServletResponse) servletResponse).setStatus(200);
        });
        if (!continued.get() && response.getStatus() == 200) {
            response.setStatus(499);
        }
        return response;
    }

    /**
     * 空的粘性库。事件流这条测试不会真的选路。
     *
     * @return 什么都不保存的粘性库
     */
    private static AffinityStore emptyStore() {
        return new AffinityStore() {
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
    }

    /**
     * 用 Spring 的 Servlet 请求包装出函数式请求。这个类型没有公开构造器。
     *
     * @param servlet 已设好路径的请求
     * @return 函数式请求
     */
    private static ServerRequest request(HttpServletRequest servlet) throws Exception {
        Class<?> type = Class.forName("org.springframework.web.servlet.function.DefaultServerRequest");
        Constructor<?> constructor = type.getDeclaredConstructor(HttpServletRequest.class, List.class);
        constructor.setAccessible(true);
        return (ServerRequest) constructor.newInstance(servlet, List.of(new MappingJackson2HttpMessageConverter()));
    }
}
