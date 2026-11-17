package dev.jasagent.gateway.ingress;

import dev.jasagent.gateway.GatewayRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 锁住仓库里的合同：上游仍指向本机和集群内 web，转发头默认不信任，网关仍是单副本。
 * 这里只读 deploy/k8s，不改它。
 */
class PublicIngressContractTest {
    /**
     * 本机上游、空的信任名单、关闭容器转发头，以及单副本都写在网关配置里。
     */
    @Test
    void gatewayConfigKeepsLocalOriginAndEmptyTrust() throws Exception {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        boolean origin = false;
        for (String line : props.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("jasagent.gateway.web-origin=")) {
                origin = true;
                assertEquals("jasagent.gateway.web-origin=http://127.0.0.1:18722", trimmed);
            }
        }
        assertTrue(origin, "缺少本机 web 上游");
        assertTrue(props.contains("jasagent.gateway.forwarded.trusted=${JASAGENT_GATEWAY_FORWARDED_TRUSTED:}"));
        assertTrue(props.contains("server.forward-headers-strategy=none"));
        assertTrue(props.contains("server.tomcat.use-relative-redirects=true"));
        assertTrue(props.contains("jasagent.gateway.replicas=1"));
        assertFalse(props.contains("tskey-"));
        assertFalse(props.contains("BEGIN PRIVATE KEY"));
        assertEquals(1, GatewayRuntime.REPLICAS);
    }

    /**
     * 集群清单仍把 web 上游指到集群内地址，网关副本数仍是 1。本测试不修改这份清单。
     */
    @Test
    void clusterManifestKeepsInClusterOriginAndOneReplica() throws Exception {
        String yaml = Files.readString(Path.of("../deploy/k8s/gateway.yaml"));
        assertTrue(yaml.contains("value: http://jasagent-web.jasagent.svc.cluster.local:18722"));
        assertTrue(yaml.contains("replicas: 1"));
        assertFalse(yaml.contains("replicas: 2"));
        assertFalse(yaml.contains(".ts.net"));
        assertFalse(yaml.contains("trycloudflare"));
        assertFalse(yaml.contains("kind: Ingress"));
        String session = Files.readString(Path.of("src/main/java/dev/jasagent/gateway/auth/GatewaySessionFilter.java"));
        assertFalse(session.contains("sendRedirect("));
    }

    /**
     * 本轮改过的类型里，方法和常量前面都有注释。
     */
    @Test
    void changedTypesCommentMethods() throws Exception {
        List<Path> files = List.of(
                Path.of("src/main/java/dev/jasagent/gateway/ingress/TrustedProxyList.java"),
                Path.of("src/main/java/dev/jasagent/gateway/ingress/TrustedForwardedHeaderFilter.java"),
                Path.of("src/main/java/dev/jasagent/gateway/config/GatewayConfiguration.java"),
                Path.of("src/main/java/dev/jasagent/gateway/auth/GatewayLocalController.java"),
                Path.of("src/main/java/dev/jasagent/gateway/auth/GatewaySessionFilter.java"),
                Path.of("src/main/java/dev/jasagent/gateway/auth/LoginFormFilter.java"));
        for (Path file : files) {
            assertCommented(file);
        }
    }

    /**
     * 可执行测试脚本存在，并且写明只覆盖验收第 1 到第 8 条。
     */
    @Test
    void executableTestScriptCoversRepositoryItemsOnly() throws Exception {
        Path script = Path.of("../scripts/test-019-gateway-public-ingress.sh");
        assertTrue(Files.isRegularFile(script));
        assertTrue(Files.isExecutable(script));
        String text = Files.readString(script);
        assertTrue(text.contains("jasagent-gateway"));
        assertTrue(text.contains("第 1 到第 8"));
        assertTrue(text.contains("第 9 到第 14"));
        // 脚本可以包含扫描用的前缀，但不能带上真实凭证。
        assertFalse(text.matches("(?s).*tskey-[A-Za-z0-9].*"));
        assertFalse(text.contains("PROBE PASSED"));
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
