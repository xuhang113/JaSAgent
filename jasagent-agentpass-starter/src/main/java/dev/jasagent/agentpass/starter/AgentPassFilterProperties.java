package dev.jasagent.agentpass.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 引入 starter 之后过滤器怎么区分代理流量和普通流量，以及向哪一个 agentpass 校验。
 * 默认开启过滤器，并把本机 18741 当作通行证服务。
 */
@ConfigurationProperties(prefix = "jasagent.agentpass")
public class AgentPassFilterProperties {
    /**
     * 通行证服务的根地址，不含路径。配置键是 {@code jasagent.agentpass.client-base-url}。
     * 校验发到 {@code /v1/tickets/verify}，核销发到 {@code /v1/tickets/redeem}。
     */
    private String clientBaseUrl = "http://127.0.0.1:18741";

    /**
     * 本服务在通行证里的目标服务名。过滤器用它对比证上的目标，不信任客户端另外声明一个服务名。
     */
    private String serviceName = "";

    /**
     * 请求没有工具头时使用的工具名。两边都空则代理流量缺少身份，直接拒绝。
     */
    private String toolName = "";

    /**
     * 请求没有租户头时使用的租户。
     */
    private String tenant = "local";

    /**
     * 路径以前缀开头时视为代理流量。默认 {@code /agent/}，因此 {@code /agent/search} 需要证，{@code /agent} 和 {@code /agentive} 不因前缀需要证。
     */
    private String agentPathPrefix = "/agent/";

    /**
     * 逗号分隔的豁免路径。命中后不看通行证头，直接放行。默认只有 {@code /health}，不要把业务路径放进来。
     */
    private String excludePaths = "/health";

    /**
     * 访问通行证服务的超时毫秒数。超时时代理流量拒绝，普通流量不受影响。
     */
    private long clientTimeoutMillis = 3000;

    /**
     * 返回通行证服务根地址。
     *
     * @return 根地址，缺省指向本机 18741
     */
    public String getClientBaseUrl() {
        return clientBaseUrl;
    }

    /**
     * 写入通行证服务根地址。
     *
     * @param clientBaseUrl 不含校验路径的根地址
     */
    public void setClientBaseUrl(String clientBaseUrl) {
        this.clientBaseUrl = clientBaseUrl;
    }

    /**
     * 返回本服务登记在证上的目标服务名。
     *
     * @return 服务名；未配置时为空字符串
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * 写入目标服务名。
     *
     * @param serviceName 本服务的名字，例如 {@code rag}
     */
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * 返回缺省工具名。
     *
     * @return 工具名；未配置时为空字符串
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 写入缺省工具名。
     *
     * @param toolName 请求未带头时使用的工具名
     */
    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    /**
     * 返回缺省租户。
     *
     * @return 租户，缺省是 {@code local}
     */
    public String getTenant() {
        return tenant;
    }

    /**
     * 写入缺省租户。
     *
     * @param tenant 请求未带头时使用的租户
     */
    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    /**
     * 返回代理路径前缀。
     *
     * @return 路径前缀，缺省是 {@code /agent/}
     */
    public String getAgentPathPrefix() {
        return agentPathPrefix;
    }

    /**
     * 写入代理路径前缀。
     *
     * @param agentPathPrefix 以这个前缀开头的路径视为代理流量
     */
    public void setAgentPathPrefix(String agentPathPrefix) {
        this.agentPathPrefix = agentPathPrefix;
    }

    /**
     * 返回尚未拆开的豁免路径配置。
     *
     * @return 逗号分隔的路径
     */
    public String getExcludePaths() {
        return excludePaths;
    }

    /**
     * 写入豁免路径配置。
     *
     * @param excludePaths 逗号分隔的精确路径
     */
    public void setExcludePaths(String excludePaths) {
        this.excludePaths = excludePaths;
    }

    /**
     * 把豁免配置拆成精确路径列表。空白项会被丢掉。
     *
     * @return 精确匹配请求路径的豁免名单；没有配置时为空列表
     */
    public List<String> excludePathList() {
        if (excludePaths == null || excludePaths.isBlank()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String item : excludePaths.split(",")) {
            if (!item.isBlank()) {
                paths.add(item.trim());
            }
        }
        return List.copyOf(paths);
    }

    /**
     * 返回调用通行证服务的超时。
     *
     * @return 超时毫秒数
     */
    public long getClientTimeoutMillis() {
        return clientTimeoutMillis;
    }

    /**
     * 写入调用超时。
     *
     * @param clientTimeoutMillis 超时毫秒数
     */
    public void setClientTimeoutMillis(long clientTimeoutMillis) {
        this.clientTimeoutMillis = clientTimeoutMillis;
    }
}
