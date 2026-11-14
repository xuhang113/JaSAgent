package dev.jasagent.gateway.nacos;

import com.alibaba.nacos.api.naming.pojo.Instance;
import dev.jasagent.gateway.agent.AgentDiscovery;
import dev.jasagent.gateway.agent.AgentInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 把 Nacos 当前的健康实例收成网关能转发的地址。不健康的实例不进入结果。
 * 结果为空时保持为空，不补 127.0.0.1 上的约定端口。
 */
public final class NacosAgentDiscovery implements AgentDiscovery {
    private final NacosHealthyLookup lookup;

    /**
     * 绑定一次查询。每次 {@link #healthyNow()} 都重新向 Nacos 要健康列表。
     *
     * @param lookup 健康实例查询
     */
    public NacosAgentDiscovery(NacosHealthyLookup lookup) {
        this.lookup = lookup;
    }

    /**
     * 查询这一刻的健康实例，并按端口、主机排序。编号优先用元数据，其次用实例编号，最后按地址生成。
     *
     * @return 可转发的实例，可能为空
     */
    @Override
    public List<AgentInstance> healthyNow() {
        List<AgentInstance> found = new ArrayList<>();
        for (Instance instance : lookup.healthyNow()) {
            if (instance == null || !instance.isHealthy()) {
                continue;
            }
            String ip = instance.getIp();
            int port = instance.getPort();
            if (ip == null || ip.isBlank() || port < 1 || port > 65535) {
                continue;
            }
            String id = metadata(instance);
            if (id.isBlank()) {
                String fromInstance = instance.getInstanceId();
                id = fromInstance == null ? "" : fromInstance.trim();
            }
            if (id.isBlank()) {
                id = NacosInstanceIds.of(ip, port);
            }
            found.add(new AgentInstance(id, ip.trim(), port));
        }
        found.sort(Comparator.comparingInt(AgentInstance::port).thenComparing(AgentInstance::host));
        return List.copyOf(found);
    }

    /**
     * 读取 agent 写入的稳定编号。没有元数据时返回空串。
     *
     * @param instance Nacos 实例
     * @return 编号或空串
     */
    private static String metadata(Instance instance) {
        Map<String, String> metadata = instance.getMetadata();
        if (metadata == null) {
            return "";
        }
        String id = metadata.get(NacosSettings.META_INSTANCE_ID);
        return id == null ? "" : id.trim();
    }
}
