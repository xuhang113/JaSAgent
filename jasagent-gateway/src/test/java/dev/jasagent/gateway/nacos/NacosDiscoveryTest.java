package dev.jasagent.gateway.nacos;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.gateway.agent.AgentInstance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关只采用 Nacos 返回的健康实例。查询失败或为空时不补静态地址。
 */
class NacosDiscoveryTest {
    /**
     * 健康实例按元数据编号映射。不健康的实例和空列表都不会变成 18731。
     */
    @Test
    void mapsOnlyHealthyInstances() {
        Instance healthy = instance("10.2.2.2", 18080, "agent-10-2-2-2-18080", true);
        Instance down = instance("10.3.3.3", 18081, "agent-10-3-3-3-18081", false);
        NacosAgentDiscovery discovery = new NacosAgentDiscovery(new NacosHealthyLookup(naming(List.of(healthy, down), null), "jasagent-agent", "DEFAULT_GROUP"));
        List<AgentInstance> found = discovery.healthyNow();
        assertEquals(1, found.size());
        assertEquals("10.2.2.2", found.get(0).host());
        assertEquals("agent-10-2-2-2-18080", found.get(0).instanceId());
        NacosAgentDiscovery empty = new NacosAgentDiscovery(new NacosHealthyLookup(naming(List.of(), null), "jasagent-agent", "DEFAULT_GROUP"));
        assertTrue(empty.healthyNow().isEmpty());
        assertFalse(empty.healthyNow().toString().contains("18731"));
    }

    /**
     * 命名服务失败时说明固定，不包含口令、地址或静态端口。
     */
    @Test
    void nacosFailureDoesNotFallBackOrLeak() throws Exception {
        String secret = "nacos-password-" + System.nanoTime();
        String address = "10.4.4.4:8848";
        Path file = Files.createTempFile("gw-nacos", ".txt");
        Files.writeString(file, secret + "\n");
        NacosSettings settings = NacosSettings.load(address, "nacos", file.toString(), "", "DEFAULT_GROUP", "jasagent-agent");
        assertFalse(settings.toString().contains(secret));
        assertFalse(settings.toString().contains(address));
        Properties properties = NacosHealthyLookup.properties(settings);
        assertEquals(secret, properties.getProperty(PropertyKeyConst.PASSWORD));
        AtomicBoolean healthyOnly = new AtomicBoolean();
        NamingService naming = (NamingService) Proxy.newProxyInstance(NamingService.class.getClassLoader(), new Class<?>[]{NamingService.class},
                (proxy, method, args) -> {
                    if ("selectInstances".equals(method.getName())) {
                        for (Object arg : args) {
                            if (arg instanceof Boolean flag) {
                                healthyOnly.set(flag);
                            }
                        }
                        throw new NacosException(500, secret + " " + address);
                    }
                    if ("toString".equals(method.getName())) {
                        return "naming";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return 1;
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType().equals(int.class)) {
                        return 0;
                    }
                    return null;
                });
        NacosHealthyLookup lookup = new NacosHealthyLookup(naming, "jasagent-agent", "DEFAULT_GROUP");
        AgentException failure = assertThrows(AgentException.class, lookup::healthyNow);
        assertTrue(healthyOnly.get());
        assertEquals("Nacos 不可用", failure.getMessage());
        assertFalse(failure.getMessage().contains(secret));
        assertFalse(failure.getMessage().contains(address));
        assertFalse(failure.getMessage().contains("18731"));
        assertEquals(null, failure.getCause());
        AgentException unavailable = assertThrows(AgentException.class, new UnavailableAgentDiscovery()::healthyNow);
        assertFalse(unavailable.getMessage().contains("127.0.0.1"));
        assertFalse(unavailable.getMessage().contains("18732"));
    }

    /**
     * 造一个 Nacos 实例。
     *
     * @param ip 主机
     * @param port 端口
     * @param id 编号
     * @param healthy 是否健康
     * @return 实例
     */
    private static Instance instance(String ip, int port, String id, boolean healthy) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setInstanceId(id);
        instance.setHealthy(healthy);
        instance.addMetadata(NacosSettings.META_INSTANCE_ID, id);
        return instance;
    }

    /**
     * 查询时只返回调用方给的列表。失败对象非空时直接抛出。
     *
     * @param instances 查询结果
     * @param failure 失败；没有时为空
     * @return 命名服务
     */
    private static NamingService naming(List<Instance> instances, NacosException failure) {
        return (NamingService) Proxy.newProxyInstance(NamingService.class.getClassLoader(), new Class<?>[]{NamingService.class},
                (proxy, method, args) -> {
                    if ("selectInstances".equals(method.getName())) {
                        if (failure != null) {
                            throw failure;
                        }
                        return instances;
                    }
                    if ("toString".equals(method.getName())) {
                        return "naming";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return 1;
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType().equals(int.class)) {
                        return 0;
                    }
                    return null;
                });
    }
}
