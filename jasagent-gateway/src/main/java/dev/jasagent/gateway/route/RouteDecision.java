package dev.jasagent.gateway.route;

import dev.jasagent.gateway.agent.AgentInstance;

/**
 * 一次粘性路由的结果。
 *
 * @param instance 选中的 agent
 * @param reassigned 这次是否把粘性改写到了另一台；原实例仍存活时为假
 */
public record RouteDecision(AgentInstance instance, boolean reassigned) {
}
