package dev.jasagent.gateway.agent;

import dev.jasagent.api.auth.Session;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.auth.AccountProfile;
import dev.jasagent.auth.AccountRoles;
import dev.jasagent.auth.MysqlAccountAuth;
import dev.jasagent.gateway.cluster.ClusterInventory;
import dev.jasagent.gateway.route.RouteDecision;
import dev.jasagent.gateway.route.StickyAgentRouter;
import dev.jasagent.gateway.store.AffinityStore;
import dev.jasagent.gateway.store.MysqlAffinityStore;
import dev.jasagent.gateway.store.UragPassword;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两个写入端共用 jasagent 库里的存活表。一个副本断开不能把另一个副本仍活着的 agent 判死。
 * 选路读的是这张共享表。口令不写进断言失败说明。
 */
class SharedAgentLivenessTest {
    private final List<String> instanceIds = new ArrayList<>();
    private final List<String> tenants = new ArrayList<>();
    private final List<String> logins = new ArrayList<>();
    private MysqlAgentLiveness broom;
    private MysqlAffinityStore affinities;
    private MysqlAccountAuth accounts;

    /**
     * 清掉这次写下的存活行、粘性租户和测试账号。库没打开时跳过对应的部分。
     */
    @AfterEach
    void cleanup() {
        if (broom != null) {
            for (String instanceId : instanceIds) {
                broom.deleteInstance(instanceId);
            }
        }
        if (affinities != null) {
            for (String tenant : tenants) {
                affinities.deleteTenant(tenant);
            }
        }
        if (accounts != null) {
            for (String login : logins) {
                accounts.deleteAccount(login);
            }
        }
    }

    /**
     * 两个副本编号不同，但读到的是同一条连接和同一次心跳。
     */
    @Test
    void twoReplicasSeeTheSameLiveness() {
        MysqlAgentLiveness first = replica();
        MysqlAgentLiveness second = replica();
        assertNotEquals(first.replicaId(), second.replicaId());
        assertEquals(first.jdbcUrl(), second.jdbcUrl());
        assertEquals(MysqlAffinityStore.DEFAULT_URL, first.jdbcUrl());
        assertFalse(first.jdbcUrl().toLowerCase().contains("password="));
        assertFalse(MysqlAgentLiveness.SCHEMA.toLowerCase().contains("password"));
        assertFalse(MysqlAgentLiveness.SCHEMA.contains("CREATE DATABASE"));
        AgentInstance agent = agent(18741);
        assertFalse(second.connectionAlive(agent));
        first.markUp(agent);
        assertTrue(first.connectionAlive(agent));
        assertTrue(second.connectionAlive(agent));
        assertEquals(first.lastHeartbeat(agent).orElseThrow(), second.lastHeartbeat(agent).orElseThrow());
        assertFalse(second.replicaConnected(agent));
        assertTrue(first.replicaConnected(agent));
    }

    /**
     * 一个副本断开后，另一个副本的连接标记和更晚的心跳都还在。两边都断开后才判死，心跳文本仍留着。
     */
    @Test
    void oneReplicaDownDoesNotEraseTheOtherHeartbeat() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-22T03:00:00Z"));
        MysqlAgentLiveness first = replica(replicaId("repa"), now::get);
        MysqlAgentLiveness second = replica(replicaId("repb"), now::get);
        AgentInstance agent = agent(18742);
        first.markUp(agent);
        Instant earlier = first.replicaHeartbeat(agent).orElseThrow();
        now.set(now.get().plusSeconds(5));
        second.markUp(agent);
        Instant later = second.replicaHeartbeat(agent).orElseThrow();
        assertTrue(later.isAfter(earlier));
        first.markDown(agent);
        assertFalse(first.replicaConnected(agent));
        assertTrue(second.replicaConnected(agent));
        assertEquals(earlier, first.replicaHeartbeat(agent).orElseThrow());
        assertEquals(later, second.replicaHeartbeat(agent).orElseThrow());
        assertEquals(later, first.lastHeartbeat(agent).orElseThrow());
        assertTrue(first.connectionAlive(agent));
        assertTrue(second.connectionAlive(agent));
        second.markDown(agent);
        assertFalse(first.connectionAlive(agent));
        assertFalse(second.connectionAlive(agent));
        assertEquals(later, second.lastHeartbeat(agent).orElseThrow());
    }

    /**
     * 崩溃后没人清标记时，过了新鲜度就不再算存活。另一个副本写下新心跳后重新算活。
     */
    @Test
    void expiredHeartbeatDoesNotKeepTheAgentAlive() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-22T04:00:00Z"));
        MysqlAgentLiveness crashed = replica(replicaId("old"), now::get);
        AgentInstance agent = agent(18743);
        crashed.markUp(agent);
        now.set(now.get().plus(MysqlAgentLiveness.FRESHNESS).plusMillis(1));
        MysqlAgentLiveness reader = replica(replicaId("read"), now::get);
        assertFalse(reader.connectionAlive(agent));
        assertTrue(reader.lastHeartbeat(agent).isPresent());
        MysqlAgentLiveness live = replica(replicaId("live"), now::get);
        live.markUp(agent);
        assertTrue(reader.connectionAlive(agent));
        assertTrue(live.replicaConnected(agent));
        assertFalse(crashed.replicaConnected(agent));
    }

    /**
     * 本副本没有连上粘性目标时，只要另一个副本的共享记录还活着，选路就不改写粘性。
     * 只读本进程表的路由器会把同一条粘性改到自己连着的另一台。
     */
    @Test
    void routingUsesSharedLivenessInsteadOfTheLocalTable() {
        AgentInstance primary = agent(18746);
        AgentInstance secondary = agent(18747);
        MysqlAgentLiveness holdsPrimary = replica();
        MysqlAgentLiveness holdsSecondary = replica();
        holdsPrimary.markUp(primary);
        holdsSecondary.markUp(secondary);
        assertFalse(holdsSecondary.replicaConnected(primary));
        assertTrue(holdsSecondary.connectionAlive(primary));
        InMemoryAgentLiveness localSecondary = new InMemoryAgentLiveness();
        localSecondary.markUp(secondary);
        assertFalse(localSecondary.connectionAlive(primary));
        String tenant = tenant();
        affinities().save(tenant, "shared-user", primary.instanceId());
        affinities().save(tenant, "local-user", primary.instanceId());
        StickyAgentRouter shared = new StickyAgentRouter(() -> List.of(primary, secondary), holdsSecondary, affinities());
        RouteDecision kept = shared.route(tenant, "shared-user");
        assertEquals(primary.instanceId(), kept.instance().instanceId());
        assertFalse(kept.reassigned());
        assertEquals(primary.instanceId(), affinities().find(tenant, "shared-user").orElseThrow());
        StickyAgentRouter local = new StickyAgentRouter(() -> List.of(primary, secondary), localSecondary, affinities());
        RouteDecision moved = local.route(tenant, "local-user");
        assertTrue(moved.reassigned());
        assertEquals(secondary.instanceId(), moved.instance().instanceId());
    }

    /**
     * 集群页的 online、connectionAlive 和 lastHeartbeat 读共享表。一个副本断开后，另一个副本仍连着就还显示在线。
     */
    @Test
    void clusterSnapshotReadsSharedLiveness() {
        AgentInstance agent = agent(18748);
        MysqlAgentLiveness writer = replica();
        MysqlAgentLiveness reader = replica();
        writer.markUp(agent);
        reader.markUp(agent);
        String tenant = tenant();
        affinities().save(tenant, "ada", agent.instanceId());
        ClusterInventory inventory = new ClusterInventory(() -> List.of(agent), reader, affinities());
        Map<String, Object> online = row(inventory, tenant);
        assertEquals(true, online.get("online"));
        assertEquals(true, online.get("connectionAlive"));
        assertEquals(List.of("ada"), online.get("stickyUsers"));
        assertFalse(String.valueOf(online.get("lastHeartbeat")).isBlank());
        writer.markDown(agent);
        Map<String, Object> stillUp = row(inventory, tenant);
        assertEquals(true, stillUp.get("connectionAlive"));
        assertEquals(true, stillUp.get("online"));
        reader.markDown(agent);
        Map<String, Object> down = row(inventory, tenant);
        assertEquals(false, down.get("online"));
        assertEquals(false, down.get("connectionAlive"));
        assertFalse(String.valueOf(down.get("lastHeartbeat")).isBlank());
    }

    /**
     * 一个副本签发的登录会话，另一个副本用同一枚令牌能认出来。两个自助用户各有租户，都不是 admin。
     */
    @Test
    void loginSessionIsVisibleToAnotherReplica() {
        MysqlAccountAuth first = accounts();
        MysqlAccountAuth second = MysqlAccountAuth.open(first.jdbcUrl(), first.username(), UragPassword.read());
        String loginA = login();
        String loginB = login();
        char[] passwordA = secret();
        char[] passwordB = secret();
        AccountProfile profileA = first.register(loginA, passwordA).toCompletableFuture().join();
        AccountProfile profileB = second.register(loginB, passwordB).toCompletableFuture().join();
        assertEquals(AccountRoles.USER, profileA.role());
        assertEquals(AccountRoles.USER, profileB.role());
        assertNotEquals(profileA.tenantId(), profileB.tenantId());
        Session session = first.login(loginA, passwordA).toCompletableFuture().join();
        Session seen = second.authenticate(session.token()).toCompletableFuture().join();
        assertEquals(loginA, seen.login());
        assertEquals(profileA.tenantId(), second.profile(seen.login()).tenantId());
        assertFalse(second.profile(loginB).tenantId().equals(profileA.tenantId()));
        assertFalse(first.jdbcUrl().toLowerCase().contains("password="));
    }

    /**
     * JDBC 地址里的 password= 会拒绝打开。说明里没有这段口令。
     */
    @Test
    void jdbcUrlRejectsEmbeddedPassword() {
        String secret = "x" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:mysql://127.0.0.1:3306/jasagent?password=" + secret + "&useSSL=false";
        AgentException failure = assertThrows(AgentException.class,
                () -> MysqlAgentLiveness.open(url, "root", Path.of("missing-password-file"), "replica-a"));
        assertFalse(String.valueOf(failure.getMessage()).contains(secret));
        assertFalse(failure.toString().contains(secret));
        assertEquals("JDBC 地址不能携带口令", failure.getMessage());
    }

    /**
     * 连不上库时，异常和对象说明都不带口令。这里的口令是当场生成的，不来自仓库。
     */
    @Test
    void failureTextDoesNotContainThePassword() {
        String secret = "y" + UUID.randomUUID().toString().replace("-", "");
        MysqlAgentLiveness closed = new MysqlAgentLiveness(
                "jdbc:mysql://127.0.0.1:1/jasagent?connectTimeout=1000&socketTimeout=1000",
                "root",
                secret,
                "replica-closed",
                MysqlAgentLiveness.FRESHNESS,
                Instant::now);
        AgentException failure = assertThrows(AgentException.class, () -> closed.connectionAlive(new AgentInstance("agent-closed", "127.0.0.1", 9)));
        assertFalse(String.valueOf(failure.getMessage()).contains(secret));
        assertFalse(failure.toString().contains(secret));
        assertFalse(closed.toString().contains(secret));
        assertTrue(closed.toString().contains("127.0.0.1:1/jasagent"));
    }

    /**
     * 打开默认库上的一个新副本，并留下清扫用的句柄。
     *
     * @return 新副本
     */
    private MysqlAgentLiveness replica() {
        return track(MysqlAgentLiveness.openDefault());
    }

    /**
     * 打开一个时钟可控的副本。编号由调用方保证唯一。
     *
     * @param replicaId 副本编号
     * @param clock 测试时钟
     * @return 绑定该时钟的副本
     */
    private MysqlAgentLiveness replica(String replicaId, Supplier<Instant> clock) {
        String password = UragPassword.read();
        if (password.isBlank()) {
            throw new IllegalStateException("没有找到本机 MySQL 口令");
        }
        return track(new MysqlAgentLiveness(MysqlAffinityStore.DEFAULT_URL, MysqlAffinityStore.DEFAULT_USER, password,
                replicaId, MysqlAgentLiveness.FRESHNESS, clock));
    }

    /**
     * 记住第一份存活表，结束时用它删除测试行。
     *
     * @param opened 刚打开的副本
     * @return 同一份对象
     */
    private MysqlAgentLiveness track(MysqlAgentLiveness opened) {
        if (broom == null) {
            broom = opened;
        }
        assertFalse(opened.toString().contains(UragPassword.read()));
        return opened;
    }

    /**
     * 生成一个不与其它测试行冲突的 agent。编号记下来，结束时删除。
     *
     * @param port 合法端口，不真正拨号
     * @return 测试实例
     */
    private AgentInstance agent(int port) {
        String instanceId = "gw024-" + port + "-" + UUID.randomUUID().toString().substring(0, 8);
        instanceIds.add(instanceId);
        return new AgentInstance(instanceId, "127.0.0.1", port);
    }

    /**
     * 生成副本编号。前缀加随机串，长度不超过主键。
     *
     * @param prefix 测试前缀
     * @return 副本编号
     */
    private static String replicaId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /**
     * 打开粘性库。同一份测试只开一次。
     *
     * @return 本机 jasagent 上的粘性库
     */
    private MysqlAffinityStore affinities() {
        if (affinities == null) {
            affinities = MysqlAffinityStore.openDefault();
        }
        return affinities;
    }

    /**
     * 生成并记住租户 id，结束时删掉该租户的粘性。
     *
     * @return 租户 id
     */
    private String tenant() {
        String tenant = "gw024-" + UUID.randomUUID().toString().substring(0, 8);
        tenants.add(tenant);
        return tenant;
    }

    /**
     * 打开账号库。口令从仓库外文件读取，不写进这个测试的源码。
     *
     * @return 账号服务
     */
    private MysqlAccountAuth accounts() {
        if (accounts == null) {
            String password = UragPassword.read();
            if (password.isBlank()) {
                throw new IllegalStateException("没有找到本机 MySQL 口令");
            }
            accounts = MysqlAccountAuth.open(MysqlAffinityStore.DEFAULT_URL, MysqlAffinityStore.DEFAULT_USER, password);
        }
        return accounts;
    }

    /**
     * 生成登录名并记到清理列表。
     *
     * @return 登录名
     */
    private String login() {
        String login = "u" + UUID.randomUUID().toString().replace("-", "");
        logins.add(login);
        return login;
    }

    /**
     * 生成只在内存里使用的口令。
     *
     * @return 口令
     */
    private static char[] secret() {
        return UUID.randomUUID().toString().replace("-", "").toCharArray();
    }

    /**
     * 取出集群清单的第一行。
     *
     * @param inventory 集群汇总
     * @param tenant 租户 id
     * @return 第一台 agent 的字段
     */
    private static Map<String, Object> row(ClusterInventory inventory, String tenant) {
        Object value = inventory.snapshot(tenant).get("agents");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) value;
        return agents.get(0);
    }
}
