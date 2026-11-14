package dev.jasagent.gateway.nacos;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.gateway.agent.AgentDiscovery;
import dev.jasagent.gateway.agent.AgentInstance;

import java.util.List;

/**
 * 没有配置 Nacos，或当前不能查询时的发现结果。调用即失败，不返回静态约定实例。
 * 登录会话和存活探测都在网关进程内，本轮网关按单副本验收。
 */
public final class UnavailableAgentDiscovery implements AgentDiscovery {
    /**
     * 创建一个总是失败的发现。
     */
    public UnavailableAgentDiscovery() {
    }

    /**
     * 拒绝发现。说明里不包含静态地址。
     *
     * @return 不会返回
     */
    @Override
    public List<AgentInstance> healthyNow() {
        throw new AgentException(ErrorCode.INTERNAL, "Nacos 不可用");
    }
}
