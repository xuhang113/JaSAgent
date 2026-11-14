package dev.jasagent.app.nacos;

import dev.jasagent.api.exception.AgentException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 就绪事件之后才注册，退出时注销。只监听回环时不登记。
 */
class NacosRegistrationTest {
    /**
     * 模拟应用就绪后，登记的是非回环地址；销毁后发现列表里没有它。
     */
    @Test
    void readyEventRegistersAndDestroyRemovesIt() {
        RecordingNaming naming = new RecordingNaming();
        NacosSettings settings = new NacosSettings("10.9.9.9:8848", "", "", "", "DEFAULT_GROUP", "jasagent-agent", "10.4.5.6", "0.0.0.0");
        NacosAgentRegistration registration = new NacosAgentRegistration(naming, settings);
        registration.registerReady(18731);
        assertTrue(registration.registered());
        assertEquals("10.4.5.6", naming.ip);
        assertEquals(18731, naming.port);
        assertEquals("jasagent-agent", naming.service);
        assertEquals(NacosInstanceIds.of("10.4.5.6", 18731), naming.instanceId);
        assertTrue(ReachableAddress.usable(naming.ip));
        assertEquals(1, naming.live.size());
        registration.destroy();
        assertFalse(registration.registered());
        assertTrue(naming.live.isEmpty());
        assertTrue(naming.events.contains("shutdown"));
    }

    /**
     * 进程只绑在 127.0.0.1 上时，就绪事件不能把实例登记出去。
     */
    @Test
    void loopbackBindDoesNotRegister() {
        RecordingNaming naming = new RecordingNaming();
        NacosSettings settings = new NacosSettings("10.9.9.9:8848", "", "", "", "DEFAULT_GROUP", "jasagent-agent", "10.4.5.6", "127.0.0.1");
        NacosAgentRegistration registration = new NacosAgentRegistration(naming, settings);
        AgentException failure = assertThrows(AgentException.class, () -> registration.registerReady(18731));
        assertEquals(ReachableAddress.LOOPBACK_BIND, failure.getMessage());
        assertFalse(failure.getMessage().contains("10.9.9.9"));
        assertTrue(naming.live.isEmpty());
        assertFalse(registration.registered());
    }

    /**
     * 没有 Nacos 地址时，就绪和销毁都不联系注册中心。
     */
    @Test
    void blankAddressSkipsRegistration() {
        RecordingNaming naming = new RecordingNaming();
        NacosSettings settings = new NacosSettings("", "", "", "", "DEFAULT_GROUP", "jasagent-agent", "", "127.0.0.1");
        NacosAgentRegistration registration = new NacosAgentRegistration(naming, settings);
        registration.registerReady(18731);
        registration.destroy();
        assertTrue(naming.events.isEmpty());
    }

    /**
     * 记下注册和注销，供断言发现列表。
     */
    private static final class RecordingNaming implements NacosNaming {
        private final List<String> events = new ArrayList<>();
        private final List<String> live = new ArrayList<>();
        private String service = "";
        private String ip = "";
        private int port;
        private String instanceId = "";

        /**
         * 记下一次注册。
         *
         * @param service 服务名
         * @param group 分组
         * @param instanceId 编号
         * @param ip 主机
         * @param port 端口
         */
        @Override
        public void register(String service, String group, String instanceId, String ip, int port) {
            this.service = service;
            this.instanceId = instanceId;
            this.ip = ip;
            this.port = port;
            live.add(ip + ":" + port);
            events.add("register");
        }

        /**
         * 从发现列表去掉这台实例。
         *
         * @param service 服务名
         * @param group 分组
         * @param ip 主机
         * @param port 端口
         */
        @Override
        public void deregister(String service, String group, String ip, int port) {
            live.remove(ip + ":" + port);
            events.add("deregister");
        }

        /**
         * 记下关闭。
         */
        @Override
        public void shutdown() {
            events.add("shutdown");
        }
    }
}
