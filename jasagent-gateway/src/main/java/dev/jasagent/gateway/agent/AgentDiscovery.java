package dev.jasagent.gateway.agent;

import java.util.List;

/**
 * 当时从 Nacos 查到的健康 agent。查询失败时抛出，不返回静态目录。
 * 登录会话在 MySQL。存活探测按网关副本写入同一库并跨副本读取。本机缺省副本数仍是 1。
 * 发现结果本身仍以这一次查询为准。
 */
public interface AgentDiscovery {
    /**
     * 查询当前健康实例。Nacos 不可用时抛出，空列表表示现在没有可转发的实例。
     * 不会改用 127.0.0.1:18731 或 127.0.0.1:18732。
     *
     * @return 这一次查到的健康实例，可能为空
     */
    List<AgentInstance> healthyNow();
}
