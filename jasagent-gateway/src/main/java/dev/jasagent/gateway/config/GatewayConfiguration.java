package dev.jasagent.gateway.config;

import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.gateway.agent.AgentInstance;
import dev.jasagent.gateway.agent.AgentLinkSupervisor;
import dev.jasagent.gateway.agent.InMemoryAgentLiveness;
import dev.jasagent.gateway.cluster.ClusterInventory;
import dev.jasagent.gateway.proxy.StickyProxy;
import dev.jasagent.gateway.route.AgentCallHeaders;
import dev.jasagent.gateway.route.StickyAgentRouter;
import dev.jasagent.gateway.store.MysqlAffinityStore;
import dev.jasagent.gateway.token.InternalToken;
import dev.jasagent.trace.lineage.RequestLineage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * 组装网关运行时需要的登录、令牌、粘性、集群和长连接。
 * 长连接默认开启。测试把它关掉，避免拨到 18731 和 18732。
 */
@Configuration
public class GatewayConfiguration {
    /**
     * 用家目录建立本机登录服务。口令仍在用户目录里，本配置不创建用户。
     *
     * @param home 用户数据根目录，缺省是运行用户的主目录
     * @return 绑定该目录的登录服务
     */
    @Bean
    public LocalAuthService localAuthService(@Value("${jasagent.home:${user.home}}") String home) {
        return new LocalAuthService(Path.of(home));
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
     * 解析允许转发的 agent。空白配置使用 127.0.0.1:18731 和 127.0.0.1:18732。
     *
     * @param raw 逗号分隔的 host:port
     * @return 按端口升序的实例
     */
    @Bean
    public List<AgentInstance> agentCatalog(@Value("${jasagent.gateway.agents:}") String raw) {
        return AgentInstance.parse(raw);
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
     * 打开本机 jasagent 粘性库。口令只从仓库外的文件读取。
     *
     * @return 粘性库
     */
    @Bean
    public MysqlAffinityStore affinityStore() {
        return MysqlAffinityStore.openDefault();
    }

    /**
     * 用目录、长连接和粘性库做路由器。
     *
     * @param agentCatalog 允许的 agent
     * @param agentLiveness 长连接状态
     * @param affinityStore 粘性库
     * @return 粘性路由器
     */
    @Bean
    public StickyAgentRouter stickyAgentRouter(List<AgentInstance> agentCatalog, InMemoryAgentLiveness agentLiveness, MysqlAffinityStore affinityStore) {
        return new StickyAgentRouter(agentCatalog, agentLiveness, affinityStore);
    }

    /**
     * 集群页读的汇总。
     *
     * @param agentCatalog 要列出的 agent
     * @param agentLiveness 长连接状态
     * @param affinityStore 粘性库
     * @return 集群汇总
     */
    @Bean
    public ClusterInventory clusterInventory(List<AgentInstance> agentCatalog, InMemoryAgentLiveness agentLiveness, MysqlAffinityStore affinityStore) {
        return new ClusterInventory(agentCatalog, agentLiveness, affinityStore);
    }

    /**
     * 转发时使用的粘性代理。租户空白时用 local。
     *
     * @param stickyAgentRouter 路由器
     * @param internalToken 内部令牌
     * @param tenantId 配置的租户 id
     * @return 粘性代理
     */
    @Bean
    public StickyProxy stickyProxy(StickyAgentRouter stickyAgentRouter, InternalToken internalToken, RequestLineage requestLineage,
                                   @Value("${jasagent.gateway.tenant-id:local}") String tenantId) {
        String tenant = tenantId == null || tenantId.isBlank() ? AgentCallHeaders.TENANT_LOCAL : tenantId;
        return new StickyProxy(stickyAgentRouter, internalToken, tenant, requestLineage);
    }

    /**
     * 在配置允许时为每台 agent 拨一条长连接。关掉这个开关时不会拨号，因此不会碰正在运行的 agent 进程。
     *
     * @param agentCatalog 要连接的 agent
     * @param agentLiveness 连接结果写到这里
     * @return 长连接管理器
     */
    @Bean
    @ConditionalOnProperty(name = "jasagent.gateway.links.enabled", havingValue = "true", matchIfMissing = true)
    public AgentLinkSupervisor agentLinkSupervisor(List<AgentInstance> agentCatalog, InMemoryAgentLiveness agentLiveness) {
        return new AgentLinkSupervisor(agentCatalog, agentLiveness);
    }
}
