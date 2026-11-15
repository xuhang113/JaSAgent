package dev.jasagent.llm.egress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对哪些地址直连、哪些必须交给 sidecar。口令只出现在输入里，不能出现在判断结果里。
 */
class EgressRouteTest {
    /**
     * 这次测试放进配置的口令。结果文本里不能有它。
     */
    private static final String SECRET = "redis-secret-not-for-logs";

    /**
     * 每个用例结束后清掉系统属性，避免影响后面的判断。
     */
    @AfterEach
    void clearProperties() {
        System.clearProperty("jasagent.nacos.server-addr");
        System.clearProperty("jasagent.jdbc.url");
        System.clearProperty("jasagent.redis.url");
        System.clearProperty("jasagent.gateway.origin");
        System.clearProperty("jasagent.direct.hosts");
    }

    /**
     * 回环、集群域名和已知服务名直连。大模型、外部工具和 MCP 主机走 sidecar。
     */
    @Test
    void clusterStaysDirectAndPublicHostsUseSidecar() {
        assertTrue(EgressRoute.direct("http://127.0.0.1:18751/search"));
        assertTrue(EgressRoute.direct("http://localhost:18741/v1/tickets"));
        assertTrue(EgressRoute.direct("http://[::1]:18741/v1/tickets"));
        assertTrue(EgressRoute.direct("http://jasagent-mysql:3306/"));
        assertTrue(EgressRoute.direct("http://jasagent-redis:6379/"));
        assertTrue(EgressRoute.direct("http://jasagent-gateway.jasagent.svc.cluster.local:18721/health"));
        assertTrue(EgressRoute.direct("http://jasagent-agentpass.jasagent.svc:18741/v1/tickets"));
        assertTrue(EgressRoute.direct("jdbc:mysql://10.1.1.1:3306/jasagent"));
        assertFalse(EgressRoute.direct("https://api.deepseek.com/chat/completions"));
        assertFalse(EgressRoute.direct("https://api.x.ai/v1/chat/completions"));
        assertFalse(EgressRoute.direct("https://mcp.example.com/rpc"));
        assertFalse(EgressRoute.direct("https://hooks.example.com/tool"));
        assertEquals("sidecar", EgressRoute.mode("https://api.deepseek.com/models"));
        assertEquals("direct", EgressRoute.mode("http://127.0.0.1:18731/health"));
    }

    /**
     * Nacos 的 HTTP 和 gRPC 端口、JDBC 主机、Redis 和网关来源都直连。配置里的口令不出现在结果里。
     */
    @Test
    void configuredClusterHostsStayDirect() {
        System.setProperty("jasagent.nacos.server-addr", "10.8.7.6:8848");
        System.setProperty("jasagent.jdbc.url", "jdbc:mysql://10.9.8.7:3306/jasagent?password=" + SECRET);
        System.setProperty("jasagent.redis.url", "redis://:" + SECRET + "@10.4.5.6:6379/0");
        System.setProperty("jasagent.gateway.origin", "http://10.2.3.4:18721");
        assertTrue(EgressRoute.direct("http://10.8.7.6:8848/nacos/v1/ns/instance"));
        assertTrue(EgressRoute.direct("http://10.8.7.6:9848/"));
        assertTrue(EgressRoute.direct("http://10.9.8.7:3306/"));
        assertTrue(EgressRoute.direct("http://10.4.5.6:6379/"));
        assertTrue(EgressRoute.direct("http://10.2.3.4:18721/health"));
        assertFalse(EgressRoute.mode("http://10.4.5.6:6379/").contains(SECRET));
        assertFalse(EgressRoute.mode("https://mcp.example.com/rpc").contains(SECRET));
        assertEquals("sidecar", EgressRoute.mode("https://mcp.example.com/rpc"));
    }
}
