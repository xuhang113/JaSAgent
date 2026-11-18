package dev.jasagent.gateway.config;

import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.auth.MysqlAccountAuth;
import dev.jasagent.gateway.agent.AgentDiscovery;
import dev.jasagent.gateway.agent.AgentLinkSupervisor;
import dev.jasagent.gateway.agent.InMemoryAgentLiveness;
import dev.jasagent.gateway.cluster.ClusterInventory;
import dev.jasagent.gateway.nacos.NacosAgentDiscovery;
import dev.jasagent.gateway.nacos.NacosHealthyLookup;
import dev.jasagent.gateway.nacos.NacosSettings;
import dev.jasagent.gateway.nacos.UnavailableAgentDiscovery;
import dev.jasagent.gateway.proxy.StickyProxy;
import dev.jasagent.gateway.route.AgentCallHeaders;
import dev.jasagent.gateway.route.StickyAgentRouter;
import dev.jasagent.gateway.ingress.TrustedProxyList;
import dev.jasagent.gateway.proxy.WebConsoleProxy;
import dev.jasagent.gateway.store.MysqlAffinityStore;
import dev.jasagent.gateway.store.UragPassword;
import dev.jasagent.gateway.token.InternalToken;
import dev.jasagent.persist.ExecutionDatabase;
import dev.jasagent.persist.SessionLedger;
import dev.jasagent.trace.lineage.RequestLineage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 组装网关运行时需要的登录、令牌、粘性、集群、长连接和控制台上游。
 * 长连接默认开启，只连当时从 Nacos 查到的实例。测试把它关掉。
 * 登录会话和存活探测都在网关进程内，本轮网关按单副本验收。
 * 转发头默认不信任任何地址，只有配置的 Ingress/Funnel 这一跳可以声明 HTTPS。
 */
@Configuration
public class GatewayConfiguration {
    /**
     * 用家目录建立文件登录服务。它只负责读出尚未迁入的运维账号，不再作为网关登录的权威存储。
     *
     * @param home 用户数据根目录，缺省是运行用户的主目录
     * @return 绑定该目录的文件登录服务
     */
    @Bean
    public LocalAuthService localAuthService(@Value("${jasagent.home:${user.home}}") String home) {
        return new LocalAuthService(Path.of(home));
    }

    /**
     * 打开和粘性库同一套 JDBC 的会话账本。聊天会话仍在 agent_session，不另建一份。
     * 口令只从仓库外文件读入内存，不写进地址或这个对象的说明。
     *
     * @param url JDBC 地址，不能包含 password=
     * @param username 数据库账号
     * @param passwordFile 仓库外的口令文件
     * @param home 工作区根，只在地址不是 MySQL 时用来放 H2
     * @return 已建表的会话账本
     */
    @Bean
    public SessionLedger sessionLedger(
            @Value("${" + MysqlAffinityStore.URL_PROPERTY + "}") String url,
            @Value("${" + MysqlAffinityStore.USER_PROPERTY + "}") String username,
            @Value("${" + MysqlAffinityStore.PASSWORD_FILE_PROPERTY + "}") String passwordFile,
            @Value("${jasagent.home:${user.home}}") String home) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JASAGENT_JDBC_URL", url);
        environment.put("JASAGENT_JDBC_USER", username);
        environment.put("JASAGENT_JDBC_PASSWORD", UragPassword.read(Path.of(passwordFile)));
        return SessionLedger.open(ExecutionDatabase.open(Path.of(home), environment));
    }

    /**
     * 打开 MySQL 账号库，并把家目录里还没有的运维账号迁入为 admin。
     * 自助注册不走这个方法。已经在库里的登录名不会被文件目录改成 admin。
     *
     * @param url JDBC 地址，不能包含 password=
     * @param username 数据库账号
     * @param passwordFile 仓库外的口令文件
     * @param home 运维账号文件所在的家目录
     * @param sessions 用来把旧的 local 聊天会话改到该用户自己的租户
     * @return 网关登录、注册和会话校验使用的账号服务
     */
    @Bean
    @Primary
    public MysqlAccountAuth mysqlAccountAuth(
            @Value("${" + MysqlAffinityStore.URL_PROPERTY + "}") String url,
            @Value("${" + MysqlAffinityStore.USER_PROPERTY + "}") String username,
            @Value("${" + MysqlAffinityStore.PASSWORD_FILE_PROPERTY + "}") String passwordFile,
            @Value("${jasagent.home:${user.home}}") String home,
            SessionLedger sessions) {
        MysqlAccountAuth accounts = MysqlAccountAuth.open(url, username, UragPassword.read(Path.of(passwordFile)));
        accounts.importOperators(Path.of(home), (login, fromTenant, toTenant) -> sessions.moveUser(fromTenant, login, toTenant));
        return accounts;
    }

    /**
     * 从环境变量 JASAGENT_INTERNAL_TOKEN 读取内部令牌。没有变量时得到未配置的令牌，不另造一个。
     *
     * @return 当前进程的内部令牌
     */
    @Bean
    public InternalToken internalToken() {
        return InternalToken.fromEnvironment();
    }

    /**
     * 请求溯源。第一次写入时才连接本机 jasagent，口令不放进这个对象。
     *
     * @return 网关和示例检索共用的调用点
     */
    @Bean
    public RequestLineage requestLineage() {
        return new RequestLineage();
    }

    /**
     * 读取 Nacos 连接参数。口令只来自仓库外文件，没有地址时后续发现直接失败。
     *
     * @param serverAddr 服务端地址
     * @param username 账号
     * @param passwordFile 口令文件
     * @param namespace 命名空间
     * @param group 分组
     * @param service 服务名
     * @return 连接参数
     */
    @Bean
    public NacosSettings nacosSettings(
            @Value("${jasagent.nacos.server-addr:}") String serverAddr,
            @Value("${jasagent.nacos.username:}") String username,
            @Value("${jasagent.nacos.password-file:}") String passwordFile,
            @Value("${jasagent.nacos.namespace:}") String namespace,
            @Value("${jasagent.nacos.group:DEFAULT_GROUP}") String group,
            @Value("${jasagent.nacos.service-name:jasagent-agent}") String service) {
        return NacosSettings.load(serverAddr, username, passwordFile, namespace, group, service);
    }

    /**
     * 发现只来自 Nacos。没配地址时返回总是失败的发现，不调用本机约定实例。
     * {@code jasagent.nacos.client=recording} 时不在这里建真实客户端，留给测试替换。
     *
     * @param settings 连接参数
     * @return 当前健康实例的发现
     */
    @Bean
    @ConditionalOnProperty(name = "jasagent.nacos.client", havingValue = "nacos", matchIfMissing = true)
    public AgentDiscovery agentDiscovery(NacosSettings settings) {
        if (!settings.configured()) {
            return new UnavailableAgentDiscovery();
        }
        return new NacosAgentDiscovery(NacosHealthyLookup.connect(settings));
    }

    /**
     * 进程内的长连接状态。路由和集群共用这一份。
     *
     * @return 空的存活表，长连接随后往里写
     */
    @Bean
    public InMemoryAgentLiveness agentLiveness() {
        return new InMemoryAgentLiveness();
    }

    /**
     * 按配置打开粘性库。地址和账号可以改，口令只从仓库外的文件读取。
     * 配了别的主机或库名时就连那一处，不再写死 127.0.0.1:3306/jasagent。
     *
     * @param url JDBC 地址，不能包含 password=
     * @param username 数据库账号
     * @param passwordFile 仓库外的口令文件
     * @return 粘性库
     */
    @Bean
    public MysqlAffinityStore affinityStore(
            @Value("${" + MysqlAffinityStore.URL_PROPERTY + "}") String url,
            @Value("${" + MysqlAffinityStore.USER_PROPERTY + "}") String username,
            @Value("${" + MysqlAffinityStore.PASSWORD_FILE_PROPERTY + "}") String passwordFile) {
        return MysqlAffinityStore.open(url, username, Path.of(passwordFile));
    }

    /**
     * 受信任的 Ingress/Funnel 这一跳。默认空白，本机和集群内明文直连都不采用 X-Forwarded-*。
     * 环境变量 {@code JASAGENT_GATEWAY_FORWARDED_TRUSTED} 可以覆盖这项。不要把凭证写进这个值。
     *
     * @param trusted 逗号分隔的 IP 或 CIDR
     * @return 用来判断这一跳能不能声明 HTTPS 的名单
     */
    @Bean
    public TrustedProxyList trustedProxyList(@Value("${" + TrustedProxyList.PROPERTY + ":}") String trusted) {
        return new TrustedProxyList(trusted);
    }

    /**
     * 控制台反向代理。上游不可用时由代理返回错误，不读取内嵌页面。
     *
     * @param origin jasagent.gateway.web-origin
     * @return 控制台上游
     */
    @Bean
    public WebConsoleProxy webConsoleProxy(@Value("${" + WebConsoleProxy.ORIGIN_PROPERTY + "}") String origin) {
        return new WebConsoleProxy(origin);
    }

    /**
     * 用 Nacos 发现、本进程长连接和粘性库做路由器。
     *
     * @param agentDiscovery 当时的健康实例
     * @param agentLiveness 长连接状态
     * @param affinityStore 粘性库
     * @return 粘性路由器
     */
    @Bean
    public StickyAgentRouter stickyAgentRouter(AgentDiscovery agentDiscovery, InMemoryAgentLiveness agentLiveness, MysqlAffinityStore affinityStore) {
        return new StickyAgentRouter(agentDiscovery, agentLiveness, affinityStore);
    }

    /**
     * 集群页读的汇总。实例来自 Nacos，不来自静态目录。
     *
     * @param agentDiscovery 当时的健康实例
     * @param agentLiveness 长连接状态
     * @param affinityStore 粘性库
     * @return 集群汇总
     */
    @Bean
    public ClusterInventory clusterInventory(AgentDiscovery agentDiscovery, InMemoryAgentLiveness agentLiveness, MysqlAffinityStore affinityStore) {
        return new ClusterInventory(agentDiscovery, agentLiveness, affinityStore);
    }

    /**
     * 转发时使用的粘性代理。账号上有租户时用那个租户；配置的租户只在没有账号时兜底。
     * 转发前用同一份会话账本拒绝他人的会话。
     *
     * @param stickyAgentRouter 路由器
     * @param internalToken 内部令牌
     * @param requestLineage 请求溯源
     * @param sessions 会话账本
     * @param tenantId 配置的缺省租户 id
     * @return 粘性代理
     */
    @Bean
    public StickyProxy stickyProxy(StickyAgentRouter stickyAgentRouter, InternalToken internalToken, RequestLineage requestLineage,
                                   SessionLedger sessions,
                                   @Value("${jasagent.gateway.tenant-id:local}") String tenantId) {
        String tenant = tenantId == null || tenantId.isBlank() ? AgentCallHeaders.TENANT_LOCAL : tenantId;
        return new StickyProxy(stickyAgentRouter, internalToken, tenant, requestLineage, sessions);
    }

    /**
     * 在配置允许时为 Nacos 上的健康实例拨长连接。关掉这个开关时不会拨号。
     * 发现为空或失败时不拨到本机约定端口。
     *
     * @param agentDiscovery 要连接的 agent
     * @param agentLiveness 连接结果写到这里
     * @return 长连接管理器
     */
    @Bean
    @ConditionalOnProperty(name = "jasagent.gateway.links.enabled", havingValue = "true", matchIfMissing = true)
    public AgentLinkSupervisor agentLinkSupervisor(AgentDiscovery agentDiscovery, InMemoryAgentLiveness agentLiveness) {
        return new AgentLinkSupervisor(agentDiscovery, agentLiveness);
    }
}
