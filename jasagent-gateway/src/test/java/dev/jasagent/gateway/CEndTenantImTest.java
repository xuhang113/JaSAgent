package dev.jasagent.gateway;

import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.auth.AccountProfile;
import dev.jasagent.auth.AccountRoles;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.auth.MysqlAccountAuth;
import dev.jasagent.gateway.agent.AgentInstance;
import dev.jasagent.gateway.agent.InMemoryAgentLiveness;
import dev.jasagent.gateway.store.MysqlAffinityStore;
import dev.jasagent.gateway.store.UragPassword;
import dev.jasagent.it.FixedTokenConfiguration;
import dev.jasagent.persist.SessionLedger;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自助注册、一人一租户、网关拒绝集群和他人会话。桩 agent 对任何请求都回 200，用来证明拒绝发生在转发之前。
 */
@SpringBootTest(properties = "jasagent.gateway.c-end-test=true")
@AutoConfigureMockMvc
@Import({FixedTokenConfiguration.class, dev.jasagent.it.CEndAgentsConfiguration.class})
public class CEndTenantImTest {
    private static final List<String> HITS = new CopyOnWriteArrayList<>();
    private static final List<String> LOGINS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<String> LIST_BODY = new AtomicReference<>("[]");
    private static Path home;
    private static HttpServer server;
    private static int port;

    /**
     * 桩端口。发现配置在扫描包外面读取它，避免把这台桩装进别的网关测试。
     *
     * @return 已经听上的端口
     */
    public static int agentPort() {
        return port;
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MysqlAccountAuth accounts;
    @Autowired
    private SessionLedger sessions;
    @Autowired
    private InMemoryAgentLiveness liveness;

    /**
     * 用桩同时充当 agent 和控制台上游。家目录是空的，避免把真实用户迁成 admin。
     *
     * @param registry 测试属性
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        if (server == null) {
            home = Files.createTempDirectory("gw020-home");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                HITS.add(path);
                byte[] body = "/api/sessions".equals(path)
                        ? LIST_BODY.get().getBytes(StandardCharsets.UTF_8)
                        : "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                if (path.startsWith("/admin")) {
                    body = "web-page".getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            port = server.getAddress().getPort();
        }
        registry.add("jasagent.home", () -> home.toString());
        registry.add("jasagent.gateway.links.enabled", () -> "false");
        registry.add("jasagent.gateway.web-origin", () -> "http://127.0.0.1:" + port);
    }

    /**
     * 把桩标成存活，并清掉上一次的访问。
     */
    @BeforeEach
    void agentUp() {
        HITS.clear();
        liveness.markUp(new AgentInstance("agent-" + port, "127.0.0.1", port));
    }

    /**
     * 停掉桩，并删掉这次注册的账号和会话。说明里不放口令。
     */
    @AfterAll
    static void cleanup() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        String password = UragPassword.read();
        try (Connection connection = DriverManager.getConnection(MysqlAffinityStore.DEFAULT_URL, "root", password)) {
            for (String login : LOGINS) {
                delete(connection, "DELETE FROM agent_session WHERE user_id = ?", login);
                delete(connection, "DELETE FROM agent_workspace WHERE user_id = ?", login);
                delete(connection, "DELETE FROM jasagent_login_session WHERE login = ?", login);
                delete(connection, "DELETE FROM jasagent_account WHERE login = ?", login);
                delete(connection, "DELETE FROM jasagent_gateway_affinity WHERE user_id = ?", login);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("测试账号无法清理");
        }
    }

    /**
     * 未登录不能读当前用户、会话或控制台。跳转是相对路径。
     */
    @Test
    void anonymousCallsDoNotLeakBusinessData() throws Exception {
        MvcResult me = mvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andReturn();
        assertTrue(me.getResponse().getContentAsString().contains("\"errorCode\":\"AUTH\""));
        assertFalse(me.getResponse().getContentAsString().contains("sessionId"));
        mvc.perform(get("/api/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sessions/someone")).andExpect(status().isUnauthorized());
        MvcResult page = mvc.perform(get("/admin/")).andReturn();
        assertEquals(302, page.getResponse().getStatus());
        String location = page.getResponse().getHeader(HttpHeaders.LOCATION);
        assertEquals("/admin/login", location);
        assertFalse(location.contains("127.0.0.1"));
        assertFalse(location.startsWith("http://"));
        assertTrue(HITS.isEmpty());
        MvcResult register = mvc.perform(get("/register")).andReturn();
        assertEquals(302, register.getResponse().getStatus());
        assertEquals("/admin/register", register.getResponse().getHeader(HttpHeaders.LOCATION));
    }

    /**
     * 注册创建普通用户和独立租户。登录后能进控制台。不能把客户端传来的租户当成加入已有租户。
     */
    @Test
    void registerCreatesAnOrdinaryTenantAndCanEnterTheConsole() throws Exception {
        String secret = secret();
        Registered first = register("local", secret);
        Registered second = register("local", secret);
        assertEquals(AccountRoles.USER, first.role);
        assertEquals(AccountRoles.USER, second.role);
        assertNotEquals(first.tenantId, second.tenantId);
        assertNotEquals("local", first.tenantId);
        assertTrue(first.tenantId.startsWith("t"));
        assertFalse(Files.exists(home.resolve(first.login).resolve("jasagent").resolve("auth").resolve("password.hash")));
        MvcResult duplicate = mvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=" + first.login + "&password=" + secret))
                .andExpect(status().isConflict())
                .andReturn();
        assertFalse(duplicate.getResponse().getContentAsString().contains(secret));
        MvcResult bad = mvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=..&password=" + secret))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertFalse(bad.getResponse().getContentAsString().contains(secret));
        String cookie = login(first.login, secret);
        mvc.perform(get("/api/me").cookie(session(cookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(AccountRoles.USER))
                .andExpect(jsonPath("$.tenantId").value(first.tenantId))
                .andExpect(jsonPath("$.login").value(first.login));
        HITS.clear();
        MvcResult console = mvc.perform(get("/admin/").cookie(session(cookie))).andExpect(status().isOk()).andReturn();
        assertTrue(console.getResponse().getContentAsString().contains("web-page"));
        assertTrue(HITS.contains("/admin/"));
    }

    /**
     * 他人会话在桩返回 200 之前就被拒绝。列表只留下自己的会话编号。新建请求仍转给自己的 agent。
     */
    @Test
    void gatewayRejectsForeignSessionsBeforeForwarding() throws Exception {
        String secret = secret();
        Registered owner = register("", secret);
        Registered other = register("", secret);
        String ownId = "own-" + UUID.randomUUID();
        String otherId = "other-" + UUID.randomUUID();
        String now = Instant.now().toString();
        sessions.save(owner.tenantId, ownId, owner.login, "自己的", "m", "p", false, now, now, "{}");
        sessions.save(other.tenantId, otherId, other.login, "别人的", "m", "p", false, now, now, "{}");
        String cookie = login(owner.login, secret);
        HITS.clear();
        mvc.perform(get("/api/sessions/" + otherId).cookie(session(cookie)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(get("/api/sessions/" + otherId + "/messages").cookie(session(cookie))).andExpect(status().isForbidden());
        mvc.perform(post("/api/sessions/" + otherId + "/delete").cookie(session(cookie))).andExpect(status().isForbidden());
        assertTrue(HITS.isEmpty());
        mvc.perform(get("/api/sessions/" + ownId).cookie(session(cookie))).andExpect(status().isOk());
        assertTrue(HITS.contains("/api/sessions/" + ownId));
        mvc.perform(get("/api/sessions/not-in-the-ledger").cookie(session(cookie))).andExpect(status().isOk());
        LIST_BODY.set("[{\"sessionId\":\"" + ownId + "\",\"title\":\"自己的\"},{\"sessionId\":\"" + otherId + "\",\"title\":\"别人的\"}]");
        MvcResult list = mvc.perform(get("/api/sessions").cookie(session(cookie))).andExpect(status().isOk()).andReturn();
        String body = list.getResponse().getContentAsString();
        assertTrue(body.contains(ownId));
        assertFalse(body.contains(otherId));
        HITS.clear();
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"新会话\"}").cookie(session(cookie)))
                .andExpect(status().isOk());
        assertTrue(HITS.contains("/api/sessions"));
    }

    /**
     * 普通用户的集群接口和集群页被网关拒绝。admin 可以看集群，但仍不能读他人会话。
     * 文件目录里的运维账号迁入后仍是 admin，旧的 local 会话改到他自己的租户。
     */
    @Test
    void clusterIsAdminOnlyAndImportedOperatorKeepsAdmin() throws Exception {
        String secret = secret();
        Registered ordinary = register("", secret);
        String cookie = login(ordinary.login, secret);
        HITS.clear();
        mvc.perform(get("/api/cluster").cookie(session(cookie)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(get("/admin/cluster").cookie(session(cookie))).andExpect(status().isForbidden());
        assertFalse(HITS.contains("/admin/cluster"));
        assertFalse(HITS.contains("/api/cluster"));
        String operator = loginName();
        LOGINS.add(operator);
        Path operatorHome = Files.createTempDirectory("gw020-operator");
        new LocalAuthService(operatorHome).addUser(new AddUser(operator, secret.toCharArray(), null, false)).toCompletableFuture().join();
        String legacyId = "legacy-" + UUID.randomUUID();
        String now = Instant.now().toString();
        sessions.save(AccountRoles.LEGACY_TENANT, legacyId, operator, "旧会话", "m", "p", false, now, now, "{}");
        accounts.importOperators(operatorHome, (login, from, to) -> sessions.moveUser(from, login, to));
        AccountProfile profile = accounts.profile(operator);
        assertTrue(profile.admin());
        assertEquals(profile.tenantId(), sessions.findBySessionId(legacyId).get(0).tenantId());
        String adminCookie = login(operator, secret);
        mvc.perform(get("/api/me").cookie(session(adminCookie)))
                .andExpect(jsonPath("$.role").value(AccountRoles.ADMIN))
                .andExpect(jsonPath("$.tenantId").value(profile.tenantId()));
        mvc.perform(get("/api/cluster").cookie(session(adminCookie))).andExpect(status().isOk()).andExpect(jsonPath("$.agents").isArray());
        HITS.clear();
        mvc.perform(get("/api/sessions/" + legacyId).cookie(session(cookie))).andExpect(status().isForbidden());
        assertTrue(HITS.isEmpty());
        mvc.perform(get("/api/sessions/" + legacyId).cookie(session(adminCookie))).andExpect(status().isOk());
        MysqlAccountAuth restarted = MysqlAccountAuth.open(accounts.jdbcUrl(), accounts.username(), UragPassword.read());
        assertEquals(operator, restarted.login(operator, secret.toCharArray()).toCompletableFuture().join().login());
        assertTrue(restarted.profile(operator).admin());
        assertTrue(restarted.jdbcUrl().startsWith("jdbc:mysql:"));
        assertFalse(restarted.jdbcUrl().toLowerCase().contains("password="));
    }

    /**
     * 注册一个用户。额外的 tenant 字段不会被当成要加入的租户。
     *
     * @param requestedTenant 客户端试图指定的租户；空白时不传
     * @param secret 口令
     * @return 注册结果
     */
    private Registered register(String requestedTenant, String secret) throws Exception {
        String login = loginName();
        LOGINS.add(login);
        String extra = requestedTenant == null || requestedTenant.isBlank() ? "" : "&tenant=" + requestedTenant;
        MvcResult result = mvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=" + login + "&password=" + secret + extra))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(secret));
        assertEquals(null, result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        AccountProfile profile = accounts.profile(login);
        assertEquals(AccountRoles.USER, profile.role());
        return new Registered(login, profile.role(), profile.tenantId());
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
        assertFalse(result.getResponse().getContentAsString().contains(secret));
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(header.startsWith("JASAGENT_SESSION="));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("Path=/"));
        assertTrue(header.contains("SameSite=Lax"));
        assertFalse(header.contains("Secure"));
        assertFalse(header.contains("127.0.0.1"));
        assertFalse(header.contains(secret));
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
     * 生成登录名。
     *
     * @return 登录名
     */
    private static String loginName() {
        return "u" + UUID.randomUUID().toString().replace("-", "");
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
     * 按登录名删除一行。表还不存在时忽略。
     *
     * @param connection 数据库连接
     * @param sql 带一个占位符的删除语句
     * @param login 登录名
     */
    private static void delete(Connection connection, String sql, String login) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, login);
            statement.executeUpdate();
        } catch (Exception ex) {
            String text = String.valueOf(ex.getMessage());
            if (text.contains("doesn't exist") || text.contains("does not exist")) {
                return;
            }
            throw new IllegalStateException("测试账号无法清理");
        }
    }

    /**
     * 这次注册出来的身份。
     *
     * @param login 登录名
     * @param role 角色
     * @param tenantId 租户
     */
    private record Registered(String login, String role, String tenantId) {
    }
}
