package dev.jasagent.app;

import dev.jasagent.app.nacos.NacosAgentRegistration;
import dev.jasagent.app.nacos.NacosInstanceIds;
import dev.jasagent.app.nacos.NacosNaming;
import dev.jasagent.app.nacos.NacosSettings;
import dev.jasagent.app.nacos.ReachableAddress;
import dev.jasagent.app.scaling.ScalingSignals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实进程就绪后向 Nacos 登记非回环地址，退出时注销，并在该地址上暴露扩缩容主指标。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "jasagent.nacos.client=recording")
@Import(NacosRegistrationSpringTest.RecordingConfig.class)
class NacosRegistrationSpringTest {
    private static final Path home = create("jasagent-nacos-home");
    private static final String advertise = ReachableAddress.require("");
    private static final RecordingNaming recording = new RecordingNaming();

    @Autowired
    private NacosAgentRegistration registration;
    @Autowired
    private NacosNaming naming;
    @Autowired
    private LocalRuntime runtime;
    @LocalServerPort
    private int port;

    /**
     * 家目录、监听地址和 Nacos 地址。口令不写进配置。
     *
     * @param registry 测试属性
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jasagent.home", home::toString);
        registry.add("server.address", () -> "0.0.0.0");
        registry.add("jasagent.nacos.server-addr", () -> "10.255.255.1:8848");
        registry.add("jasagent.nacos.advertise-host", () -> advertise);
        registry.add("jasagent.nacos.client", () -> "recording");
        registry.add("jasagent.egress.enabled", () -> "false");
        registry.add("jasagent.agentpass.enabled", () -> "false");
    }

    /**
     * 就绪后登记的地址能连上，指标在这个地址上，销毁后发现结果里没有这台实例。
     */
    @Test
    void registersAfterReadyAndDeregistersOnDestroy() throws Exception {
        assertTrue(naming instanceof RecordingNaming);
        assertEquals(advertise, recording.ip);
        assertEquals(port, recording.port);
        assertTrue(ReachableAddress.usable(recording.ip));
        assertEquals(NacosInstanceIds.of(advertise, port), recording.instanceId);
        assertEquals(NacosSettings.DEFAULT_SERVICE, recording.service);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(recording.ip, recording.port), 2000);
        }
        runtime.stageExecution("metric-1", "session-a", "RUNNING");
        runtime.stageExecution("metric-2", "session-b", "ACCEPTED");
        runtime.stageExecution("metric-3", "session-c", "SUCCEEDED");
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://" + recording.ip + ":" + recording.port + ScalingSignals.PATH))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains(ScalingSignals.PRIMARY_METRIC + " 2"));
        assertTrue(response.body().contains(ScalingSignals.CPU_SUPPLEMENT_METRIC));
        assertTrue(response.body().contains("主指标"));
        assertFalse(response.body().contains("10.255.255.1"));
        registration.destroy();
        assertFalse(registration.registered());
        assertTrue(recording.live.isEmpty());
        assertTrue(recording.events.contains("deregister"));
    }

    /**
     * 测试用的命名操作。正式配置在 recording 模式下不会再创建真实客户端。
     */
    @TestConfiguration
    static class RecordingConfig {
        /**
         * 提供不连网的命名操作。
         *
         * @return 记录注册和注销的实现
         */
        @Bean
        @ConditionalOnProperty(name = "jasagent.nacos.client", havingValue = "recording")
        NacosNaming nacosNaming() {
            return recording;
        }
    }

    /**
     * 创建临时目录。失败时测试无法继续。
     *
     * @param prefix 目录前缀
     * @return 临时目录
     */
    private static Path create(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception ex) {
            throw new IllegalStateException("临时目录没有建成");
        }
    }

    /**
     * 记下注册出去的实例，注销后列表为空。
     */
    static final class RecordingNaming implements NacosNaming {
        private final List<String> events = new ArrayList<>();
        private final List<String> live = new ArrayList<>();
        private String service = "";
        private String ip = "";
        private int port;
        private String instanceId = "";

        /**
         * 记下注册。
         *
         * @param service 服务名
         * @param group 分组
         * @param instanceId 编号
         * @param ip 主机
         * @param port 端口
         */
        @Override
        public synchronized void register(String service, String group, String instanceId, String ip, int port) {
            this.service = service;
            this.instanceId = instanceId;
            this.ip = ip;
            this.port = port;
            live.add(ip + ":" + port);
            events.add("register");
        }

        /**
         * 从列表去掉实例。
         *
         * @param service 服务名
         * @param group 分组
         * @param ip 主机
         * @param port 端口
         */
        @Override
        public synchronized void deregister(String service, String group, String ip, int port) {
            live.remove(ip + ":" + port);
            events.add("deregister");
        }

        /**
         * 记下关闭。
         */
        @Override
        public synchronized void shutdown() {
            events.add("shutdown");
        }
    }
}
