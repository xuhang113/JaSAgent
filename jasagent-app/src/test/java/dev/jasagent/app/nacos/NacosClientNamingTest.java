package dev.jasagent.app.nacos;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import dev.jasagent.api.exception.AgentException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * nacos-client 适配只登记非回环地址，注销后发现列表为空，异常里不带口令。
 */
class NacosClientNamingTest {
    /**
     * 注册后能查到非回环地址；注销后列表里没有这台实例。回环地址不会发出去。
     */
    @Test
    void registerAndDeregisterRoundTrip() throws Exception {
        List<Instance> instances = new ArrayList<>();
        AtomicBoolean shut = new AtomicBoolean();
        NamingService naming = naming(instances, shut, null);
        NacosClientNaming client = new NacosClientNaming(naming);
        assertThrows(AgentException.class, () -> client.register("jasagent-agent", "DEFAULT_GROUP", "agent-127-0-0-1-18731", "127.0.0.1", 18731));
        assertTrue(instances.isEmpty());
        client.register("jasagent-agent", "DEFAULT_GROUP", "agent-10-0-0-8-18731", "10.0.0.8", 18731);
        assertEquals(1, instances.size());
        assertEquals("10.0.0.8", instances.get(0).getIp());
        assertEquals(18731, instances.get(0).getPort());
        assertEquals("agent-10-0-0-8-18731", instances.get(0).getMetadata().get(NacosSettings.META_INSTANCE_ID));
        assertTrue(instances.get(0).isEphemeral());
        assertFalse(ReachableAddress.usable("127.0.0.1"));
        assertTrue(ReachableAddress.usable(instances.get(0).getIp()));
        @SuppressWarnings("unchecked")
        List<Instance> listed = naming.selectInstances("jasagent-agent", "DEFAULT_GROUP", true);
        assertEquals(1, listed.size());
        client.deregister("jasagent-agent", "DEFAULT_GROUP", "10.0.0.8", 18731);
        assertTrue(naming.selectInstances("jasagent-agent", "DEFAULT_GROUP", true).isEmpty());
        client.shutdown();
        assertTrue(shut.get());
    }

    /**
     * 客户端失败时，异常和设置对象的文本都不包含口令或地址。
     */
    @Test
    void failuresDoNotExposeSecrets() throws Exception {
        String secret = "nacos-password-" + System.nanoTime();
        String address = "10.9.9.9:8848";
        Path file = Files.createTempFile("nacos-pass", ".txt");
        Files.writeString(file, secret + "\n");
        NacosSettings settings = NacosSettings.load(address, "nacos", file.toString(), "", "DEFAULT_GROUP", "jasagent-agent", "10.0.0.8", "0.0.0.0");
        assertFalse(settings.toString().contains(secret));
        assertFalse(settings.toString().contains(address));
        Properties properties = NacosClientNaming.properties(settings);
        assertEquals(secret, properties.getProperty(PropertyKeyConst.PASSWORD));
        assertEquals(address, properties.getProperty(PropertyKeyConst.SERVER_ADDR));
        NamingService naming = naming(new ArrayList<>(), new AtomicBoolean(), new NacosException(500, secret + " " + address));
        NacosClientNaming client = new NacosClientNaming(naming);
        AgentException failure = assertThrows(AgentException.class,
                () -> client.register("jasagent-agent", "DEFAULT_GROUP", "agent-10-0-0-8-18731", "10.0.0.8", 18731));
        assertFalse(failure.getMessage().contains(secret));
        assertFalse(failure.getMessage().contains(address));
        assertEquals(null, failure.getCause());
    }

    /**
     * 用动态代理充当命名服务，只实现这次测试会碰到的方法。
     *
     * @param instances 已登记的实例
     * @param shut 是否已经关闭
     * @param failure 非空时注册直接失败
     * @return 命名服务
     */
    private static NamingService naming(List<Instance> instances, AtomicBoolean shut, NacosException failure) {
        return (NamingService) Proxy.newProxyInstance(NamingService.class.getClassLoader(), new Class<?>[]{NamingService.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("registerInstance".equals(name) && args != null && args.length == 3 && args[2] instanceof Instance instance) {
                        if (failure != null) {
                            throw failure;
                        }
                        instances.add(instance);
                        return null;
                    }
                    if ("deregisterInstance".equals(name) && args != null && args.length == 4) {
                        String ip = (String) args[2];
                        int port = (Integer) args[3];
                        instances.removeIf(item -> ip.equals(item.getIp()) && item.getPort() == port);
                        return null;
                    }
                    if ("selectInstances".equals(name)) {
                        return List.copyOf(instances);
                    }
                    if ("shutDown".equals(name)) {
                        shut.set(true);
                        return null;
                    }
                    if ("toString".equals(name)) {
                        return "naming";
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType().equals(int.class)) {
                        return 0;
                    }
                    if (method.getReturnType().equals(String.class)) {
                        return "";
                    }
                    return null;
                });
    }
}
