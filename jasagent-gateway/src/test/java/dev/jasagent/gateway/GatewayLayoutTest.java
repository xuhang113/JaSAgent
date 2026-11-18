package dev.jasagent.gateway;

import dev.jasagent.gateway.proxy.WebConsoleProxy;
import dev.jasagent.gateway.store.MysqlAffinityStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 网关源码树不再携带内嵌控制台，单副本口径写在配置和源码里。改过的类型都有注释。
 */
class GatewayLayoutTest {
    /**
     * 默认监听 127.0.0.1:18721，控制台上游和粘性库地址写在配置里。
     */
    @Test
    void defaultsStayOnLoopbackGateway() throws Exception {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(props.contains("server.address=127.0.0.1"));
        assertTrue(props.contains("server.port=18721"));
        assertTrue(props.contains("jasagent.gateway.web-origin=http://127.0.0.1:18722"));
        assertTrue(props.contains(MysqlAffinityStore.DEFAULT_URL));
        assertTrue(props.contains("jasagent.gateway.affinity.username=root"));
        assertTrue(props.contains("${user.home}/.config/urag-mysql-root.password"));
        assertTrue(props.contains(GatewayRuntime.SINGLE_REPLICA));
        assertEquals(1, GatewayRuntime.REPLICAS);
        assertEquals(18721, GatewayRuntime.DEFAULT_PORT);
        String source = Files.readString(Path.of("src/main/java/dev/jasagent/gateway/agent/InMemoryAgentLiveness.java"));
        assertTrue(source.contains(GatewayRuntime.SINGLE_REPLICA));
    }

    /**
     * gateway-admin 不再作为控制台来源出现在网关源码树。页面源码留在 jasagent-web。
     */
    @Test
    void consoleSourceIsNotEmbedded() throws Exception {
        assertFalse(Files.exists(Path.of("src/main/resources/gateway-admin")));
        assertFalse(Files.exists(Path.of("src/main/java/dev/jasagent/gateway/admin")));
        assertTrue(Files.exists(Path.of("../jasagent-web/frontend/src/views/LoginView.vue")));
        assertTrue(Files.exists(Path.of("../jasagent-web/frontend/src/views/ConsoleView.vue")));
        String vite = Files.readString(Path.of("../jasagent-web/frontend/vite.config.js"));
        assertFalse(vite.contains("jasagent-gateway/src/main/resources/gateway-admin"));
        assertTrue(vite.contains("../target/classes/admin"));
        try (var walk = Files.walk(Path.of("src"))) {
            assertFalse(walk.anyMatch(path -> path.toString().contains("gateway-admin")));
        }
    }

    /**
     * 带了用户信息的上游地址要拒绝，说明里没有口令。
     */
    @Test
    void webOriginRejectsUserInfo() {
        String secret = "origin-secret";
        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new WebConsoleProxy("http://user:" + secret + "@127.0.0.1:18722"));
        assertFalse(String.valueOf(failure.getMessage()).contains(secret));
        WebConsoleProxy proxy = new WebConsoleProxy("http://127.0.0.1:18722/");
        assertEquals("http://127.0.0.1:18722", proxy.origin().toString());
        assertFalse(proxy.toString().contains(secret));
    }

    /**
     * 本轮新增和改动的类型里，方法和常量前面都有注释。
     */
    @Test
    void changedTypesCommentMethods() throws Exception {
        List<Path> files = List.of(
                Path.of("src/main/java/dev/jasagent/gateway/GatewayRuntime.java"),
                Path.of("src/main/java/dev/jasagent/gateway/GatewayApplication.java"),
                Path.of("src/main/java/dev/jasagent/gateway/proxy/WebConsoleProxy.java"),
                Path.of("src/main/java/dev/jasagent/gateway/proxy/WebConsoleRoutes.java"),
                Path.of("src/main/java/dev/jasagent/gateway/route/GatewayPaths.java"),
                Path.of("src/main/java/dev/jasagent/gateway/auth/GatewaySessionFilter.java"),
                Path.of("src/main/java/dev/jasagent/gateway/store/MysqlAffinityStore.java"),
                Path.of("src/main/java/dev/jasagent/gateway/store/UragPassword.java"),
                Path.of("src/main/java/dev/jasagent/gateway/config/GatewayConfiguration.java"),
                Path.of("src/main/java/dev/jasagent/gateway/agent/InMemoryAgentLiveness.java"),
                Path.of("src/main/java/dev/jasagent/gateway/agent/AgentDiscovery.java"),
                Path.of("src/main/java/dev/jasagent/gateway/agent/AgentLinkSupervisor.java"),
                Path.of("src/main/java/dev/jasagent/gateway/agent/AgentInstance.java"),
                Path.of("src/main/java/dev/jasagent/gateway/nacos/NacosSettings.java"),
                Path.of("src/main/java/dev/jasagent/gateway/nacos/NacosInstanceIds.java"),
                Path.of("src/main/java/dev/jasagent/gateway/nacos/NacosHealthyLookup.java"),
                Path.of("src/main/java/dev/jasagent/gateway/nacos/NacosAgentDiscovery.java"),
                Path.of("src/main/java/dev/jasagent/gateway/nacos/UnavailableAgentDiscovery.java"),
                Path.of("src/main/java/dev/jasagent/gateway/route/StickyAgentRouter.java"),
                Path.of("src/main/java/dev/jasagent/gateway/cluster/ClusterInventory.java"),
                Path.of("src/main/java/dev/jasagent/gateway/cluster/ClusterAccessFilter.java"),
                Path.of("src/main/java/dev/jasagent/gateway/proxy/ChatSessionGate.java"),
                Path.of("../jasagent-web/src/main/java/dev/jasagent/web/upstream/WebUpstream.java"),
                Path.of("../jasagent-web/src/main/java/dev/jasagent/web/upstream/WebUpstreamApplication.java"),
                Path.of("../jasagent-web/src/main/java/dev/jasagent/web/upstream/ConsolePages.java"),
                Path.of("../jasagent-web/src/main/java/dev/jasagent/web/upstream/ConsoleResources.java"),
                Path.of("../jasagent-app/src/main/java/dev/jasagent/app/JasagentApplication.java"),
                Path.of("../jasagent-app/src/main/java/dev/jasagent/app/AgentPorts.java"));
        for (Path file : files) {
            assertCommented(file);
        }
    }

    /**
     * 方法、构造器和常量声明的上一节应是注释结束。注解和空行可以夹在中间。
     *
     * @param file 要检查的源文件
     */
    private static void assertCommented(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!(line.startsWith("public ") || line.startsWith("private ") || line.startsWith("protected "))) {
                continue;
            }
            if (line.contains(" class ") || line.contains(" interface ") || line.contains(" record ") || line.contains(" enum ")) {
                continue;
            }
            boolean method = line.contains("(") && !line.contains("=");
            boolean constant = line.contains("static") && line.contains("=");
            if (!method && !constant) {
                continue;
            }
            int cursor = i - 1;
            boolean documented = false;
            while (cursor >= 0) {
                String previous = lines.get(cursor).trim();
                if (previous.endsWith("*/")) {
                    documented = true;
                    break;
                }
                if (previous.equals("}") || previous.contains(" class ") || previous.contains(" interface ") || previous.contains(" record ")) {
                    break;
                }
                cursor--;
            }
            if (!documented) {
                fail(file + " 缺少注释: " + line);
            }
        }
    }
}
