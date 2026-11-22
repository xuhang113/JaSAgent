package dev.jasagent.gateway;

import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.AccountProfile;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.auth.MysqlAccountAuth;
import dev.jasagent.gateway.agent.AgentDiscovery;
import dev.jasagent.gateway.agent.AgentInstance;
import dev.jasagent.gateway.agent.AgentLiveness;
import dev.jasagent.gateway.store.MysqlAffinityStore;
import dev.jasagent.it.FixedTokenConfiguration;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 已登录请求按粘性转到 agent，并带上四个约定头。Cookie 和 Authorization 不转发。
 * 会话头是会话编号，不是登录令牌。目标断开后改道，两台都断开时保留粘性行。
 */
@SpringBootTest(properties = "jasagent.gateway.forward-test=true")
@AutoConfigureMockMvc
@Import({FixedTokenConfiguration.class, dev.jasagent.it.DiscoveredAgentsConfiguration.class})
public class GatewayForwardTest {
    /**
     * 测试用的发现。实例由桩端口填入，不读静态目录。
     */
    public static final MutableAgentDiscovery discovered = new MutableAgentDiscovery();
    private static final String TOKEN = FixedTokenConfiguration.TOKEN;
    private static final String USER = "forward-user";
    private static final String PASSWORD = "test-password-1";
    private static final String TENANT = "gw015fwd";
    private static final List<Hit> hits = new CopyOnWriteArrayList<>();
    private static Path home;
    private static HttpServer first;
    private static HttpServer second;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AgentLiveness liveness;
    @Autowired
    private MysqlAffinityStore store;
    @Autowired
    private MysqlAccountAuth auth;

    /**
     * 拉起两台桩 agent，并在上下文创建前放好测试令牌。
     *
     * @param registry 测试属性
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        ensureAgents();
        registry.add("jasagent.home", () -> home.toString());
        registry.add("jasagent.gateway.links.enabled", () -> "false");
        registry.add("jasagent.gateway.tenant-id", () -> TENANT);
        registry.add("jasagent.gateway.web-origin", () -> "http://127.0.0.1:9");
        int firstPort = first.getAddress().getPort();
        int secondPort = second.getAddress().getPort();
        discovered.set(List.of(
                new AgentInstance("agent-" + firstPort, "127.0.0.1", firstPort),
                new AgentInstance("agent-" + secondPort, "127.0.0.1", secondPort)));
    }

    /**
     * 每次请求前两台都标成存活，并清掉上次的访问记录。
     */
    @BeforeEach
    void bothAlive() {
        hits.clear();
        store.deleteTenant(TENANT);
        store.deleteTenant(auth.profile(USER).tenantId());
        for (AgentInstance agent : discovered.healthyNow()) {
            liveness.markUp(agent);
        }
    }

    /**
     * 关掉两台桩 agent。
     */
    @AfterAll
    static void cleanup() {
        if (first != null) {
            first.stop(0);
        }
        if (second != null) {
            second.stop(0);
        }
    }

    /**
     * 连续两次落到同一台存活 agent。头是四个约定名字，没有 Cookie 和 Authorization。
     * 会话头不是 Cookie 里的登录令牌。路径里有会话编号时用路径上的编号。
     */
    @Test
    void sameUserStaysAndHeadersOmitBrowserSecrets() throws Exception {
        String cookie = login();
        Session session = auth.authenticate(cookie).toCompletableFuture().join();
        mvc.perform(get("/api/sessions").cookie(new Cookie("JASAGENT_SESSION", cookie))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + cookie))
                .andExpect(status().isOk());
        mvc.perform(get("/api/sessions").cookie(new Cookie("JASAGENT_SESSION", cookie)))
                .andExpect(status().isOk());
        assertEquals(2, hits.size());
        assertEquals(hits.get(0).port, hits.get(1).port);
        String tenant = tenantOf(session.login());
        assertHeaders(hits.get(0), session.sessionId(), cookie, tenant);
        MvcResult pathed = mvc.perform(get("/api/sessions/path-session-1").cookie(new Cookie("JASAGENT_SESSION", cookie)))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("path-session-1", header(hits.get(2), "x-jasagent-session"));
        assertFalse(pathed.getResponse().getContentAsString().contains(TOKEN));
        assertFalse(String.valueOf(pathed.getResponse().getHeader("X-Jasagent-Token")).contains(TOKEN));
        String saved = store.find(tenant, USER).orElseThrow();
        assertEquals("agent-" + hits.get(0).port, saved);
    }

    /**
     * 目标长连接断开后改到另一台，并写回粘性。两台都断开时请求失败，粘性行还在。
     */
    @Test
    void offlineAgentReroutesAndBothDownKeepsTheRow() throws Exception {
        String cookie = login();
        String tenant = tenantOf(USER);
        mvc.perform(get("/api/sessions").cookie(new Cookie("JASAGENT_SESSION", cookie))).andExpect(status().isOk());
        AgentInstance current = agent(hits.get(0).port);
        liveness.markDown(current);
        hits.clear();
        mvc.perform(get("/api/sessions").cookie(new Cookie("JASAGENT_SESSION", cookie))).andExpect(status().isOk());
        assertEquals(1, hits.size());
        assertNotEquals(current.port(), hits.get(0).port);
        assertEquals("agent-" + hits.get(0).port, store.find(tenant, USER).orElseThrow());
        String kept = store.find(tenant, USER).orElseThrow();
        for (AgentInstance agent : discovered.healthyNow()) {
            liveness.markDown(agent);
        }
        mvc.perform(get("/api/sessions").cookie(new Cookie("JASAGENT_SESSION", cookie)))
                .andExpect(status().isServiceUnavailable());
        assertEquals(kept, store.find(tenant, USER).orElseThrow());
    }

    /**
     * 发现结果被清空后，请求失败，不改打到 18731 或 18732 的静态地址，粘性行还在。
     */
    @Test
    void emptyDiscoveryFailsWithoutStaticFallback() throws Exception {
        String cookie = login();
        String tenant = tenantOf(USER);
        mvc.perform(get("/api/sessions").cookie(new Cookie("JASAGENT_SESSION", cookie))).andExpect(status().isOk());
        String kept = store.find(tenant, USER).orElseThrow();
        List<AgentInstance> previous = List.copyOf(discovered.healthyNow());
        discovered.set(List.of());
        try {
            MvcResult failed = mvc.perform(get("/api/sessions").cookie(new Cookie("JASAGENT_SESSION", cookie)))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn();
            String body = failed.getResponse().getContentAsString();
            assertFalse(body.contains("127.0.0.1:18731"));
            assertFalse(body.contains("127.0.0.1:18732"));
            assertFalse(body.contains(TOKEN));
            assertEquals(kept, store.find(tenant, USER).orElseThrow());
        } finally {
            discovered.set(previous);
        }
    }

    /**
     * 核对四个内部头，并确认浏览器凭据没有被转发出去。
     *
     * @param hit 桩收到的请求
     * @param sessionId 期望的会话编号
     * @param cookie 登录令牌，不能出现在会话头里
     * @param tenant 当前账号自己的租户
     */
    private static void assertHeaders(Hit hit, String sessionId, String cookie, String tenant) {
        assertEquals(TOKEN, header(hit, "x-jasagent-token"));
        assertEquals(tenant, header(hit, "x-jasagent-tenant"));
        assertNotEquals(TENANT, tenant);
        assertEquals(USER, header(hit, "x-jasagent-user"));
        assertEquals(sessionId, header(hit, "x-jasagent-session"));
        assertNotEquals(cookie, header(hit, "x-jasagent-session"));
        assertFalse(hit.headers.containsKey("cookie"));
        assertFalse(hit.headers.containsKey("authorization"));
        assertFalse(header(hit, "x-jasagent-session").contains(TOKEN));
    }

    /**
     * 按端口找回目录里的 agent。
     *
     * @param port 桩端口
     * @return 对应实例
     */
    private AgentInstance agent(int port) {
        for (AgentInstance agent : discovered.healthyNow()) {
            if (agent.port() == port) {
                return agent;
            }
        }
        throw new AssertionError("没有这台 agent");
    }

    /**
     * 读取桩收到的某个头。没有时是空串。
     *
     * @param hit 桩收到的请求
     * @param name 小写头名
     * @return 第一个取值
     */
    private static String header(Hit hit, String name) {
        List<String> values = hit.headers.get(name);
        if (values == null || values.isEmpty() || values.get(0) == null) {
            return "";
        }
        return values.get(0);
    }

    /**
     * 登录并返回会话 Cookie 的值。
     *
     * @return Cookie 值
     */
    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=" + USER + "&password=" + PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        int end = header.indexOf(';');
        String value = header.substring("JASAGENT_SESSION=".length(), end < 0 ? header.length() : end);
        assertFalse(result.getResponse().getContentAsString().contains(PASSWORD));
        return value;
    }

    /**
     * 当前测试用户被迁入后的租户。粘性键和转发头都用它，不用配置里的缺省租户。
     *
     * @param login 登录名
     * @return 该用户自己的租户
     */
    private String tenantOf(String login) {
        AccountProfile profile = auth.profile(login);
        return profile.tenantId();
    }

    /**
     * 启动两台只回固定正文的桩，并建好测试用户。
     */
    private static void ensureAgents() throws Exception {
        if (first != null) {
            return;
        }
        home = Files.createTempDirectory("gw015-forward");
        first = listen();
        second = listen();
        new LocalAuthService(home).addUser(new AddUser(USER, PASSWORD.toCharArray(), null, false)).toCompletableFuture().join();
    }

    /**
     * 在回环地址上听一个随机端口，把请求头记下来。
     *
     * @return 已经启动的桩
     */
    private static HttpServer listen() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Hit hit = new Hit();
            hit.port = server.getAddress().getPort();
            hit.headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((key, value) -> hit.headers.put(key.toLowerCase(), List.copyOf(value)));
            hits.add(hit);
            byte[] body = "from-agent".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    /**
     * 测试里可替换的发现结果。
     */
    public static final class MutableAgentDiscovery implements AgentDiscovery {
        private volatile List<AgentInstance> instances = List.of();

        /**
         * 换成这一次的健康实例。
         *
         * @param instances 健康实例
         */
        private void set(List<AgentInstance> instances) {
            this.instances = List.copyOf(instances);
        }

        /**
         * 返回最近一次放进来的实例。
         *
         * @return 健康实例
         */
        @Override
        public List<AgentInstance> healthyNow() {
            return instances;
        }
    }

    /**
     * 桩收到的一次请求。头名已转成小写。
     */
    private static final class Hit {
        private int port;
        private Map<String, List<String>> headers = new LinkedHashMap<>();
    }
}
