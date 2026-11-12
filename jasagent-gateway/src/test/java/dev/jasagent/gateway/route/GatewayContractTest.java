package dev.jasagent.gateway.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.gateway.agent.AgentInstance;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import dev.jasagent.gateway.token.InternalToken;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部头必须用 agent 约定的四个名字。模型目录保留 DeepSeek 并接受 grok-4.7。
 * 执行详情带上这次落到的实例和地址。
 */
class GatewayContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 头名和令牌环境变量名与运行时约定一致，不能换成别的名字。
     */
    @Test
    void headerNamesMatchTheAgentContract() {
        assertEquals("X-Jasagent-Token", AgentCallHeaders.TOKEN);
        assertEquals("X-Jasagent-Tenant", AgentCallHeaders.TENANT);
        assertEquals("X-Jasagent-User", AgentCallHeaders.USER);
        assertEquals("X-Jasagent-Session", AgentCallHeaders.SESSION);
        assertEquals("JASAGENT_INTERNAL_TOKEN", AgentCallHeaders.TOKEN_ENV);
        assertEquals("local", AgentCallHeaders.TENANT_LOCAL);
    }

    /**
     * 转发头写上四个约定字段，清掉登录 Cookie 和浏览器伪造的令牌。
     * 会话头是会话编号，不是登录令牌。
     */
    @Test
    void applyWritesTheFourAgentHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, "JASAGENT_SESSION=secret-cookie");
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer secret-cookie");
        headers.set("X-Jasagent-Token", "spoofed");
        headers.set("X-Jasagent-Internal-Token", "spoofed");
        AgentCallHeaders.apply(headers, InternalToken.of("gateway-test-token"), "local", "ada", "session-1");
        assertEquals("gateway-test-token", headers.getFirst("X-Jasagent-Token"));
        assertEquals("local", headers.getFirst("X-Jasagent-Tenant"));
        assertEquals("ada", headers.getFirst("X-Jasagent-User"));
        assertEquals("session-1", headers.getFirst("X-Jasagent-Session"));
        assertNull(headers.getFirst(HttpHeaders.COOKIE));
        assertNull(headers.getFirst(HttpHeaders.AUTHORIZATION));
        assertNull(headers.getFirst("X-Jasagent-Internal-Token"));
        assertNull(headers.getFirst("X-Jasagent-Tenant-Id"));
        assertNull(headers.getFirst("X-Jasagent-User-Id"));
        assertNull(headers.getFirst("X-Jasagent-Session-Id"));
    }

    /**
     * 令牌对象的文本不能带出令牌原文。环境变量缺失时不另造令牌。
     */
    @Test
    void tokenTextDoesNotLeakAndMissingEnvStaysUnset() {
        InternalToken token = InternalToken.of("gateway-test-token");
        assertFalse(token.toString().contains("gateway-test-token"));
        InternalToken missing = InternalToken.fromEnvironment(Map.of());
        assertFalse(missing.configured());
        String fromProcess = System.getenv(AgentCallHeaders.TOKEN_ENV);
        if (fromProcess != null && !fromProcess.isBlank()) {
            InternalToken loaded = InternalToken.fromEnvironment();
            assertTrue(loaded.configured());
            assertFalse(loaded.toString().contains(fromProcess.trim()));
        }
    }

    /**
     * 登录页、控制台和集群留在网关。业务接口才转发。自助注册没有本地入口。
     */
    @Test
    void pagesStayOnTheGatewayAndApisProxy() {
        assertTrue(GatewayPaths.local(HttpMethod.POST, "/login"));
        assertTrue(GatewayPaths.local(HttpMethod.GET, "/admin/"));
        assertTrue(GatewayPaths.local(HttpMethod.GET, "/admin/assets/index-BA871a2a.js"));
        assertTrue(GatewayPaths.local(HttpMethod.GET, "/api/cluster"));
        assertTrue(GatewayPaths.local(HttpMethod.GET, "/api/me"));
        assertFalse(GatewayPaths.proxied(HttpMethod.POST, "/login"));
        assertFalse(GatewayPaths.proxied(HttpMethod.POST, "/register"));
        assertFalse(GatewayPaths.local(HttpMethod.POST, "/register"));
        assertTrue(GatewayPaths.proxied(HttpMethod.GET, "/api/sessions"));
        assertTrue(GatewayPaths.models(HttpMethod.GET, "/api/models"));
        assertTrue(GatewayPaths.execution(HttpMethod.GET, "/api/executions/exec-1"));
        assertFalse(GatewayPaths.execution(HttpMethod.GET, "/api/executions/exec-1/events"));
    }

    /**
     * 已有的 DeepSeek 保持顺序，末尾补上 grok-4.7。已经有 grok 时不重复。
     */
    @Test
    void modelListKeepsDeepSeekAndAcceptsGrok() throws Exception {
        String body = UpstreamRewriter.rewrite(HttpMethod.GET, "/api/models", """
                {"defaultModel":"deepseek-flash","models":[
                  {"id":"deepseek-flash","provider":"deepseek","label":"deepseek-flash"},
                  {"id":"deepseek-v4-pro","provider":"deepseek","label":"deepseek-v4-pro"}
                ]}
                """, null);
        JsonNode models = mapper.readTree(body).get("models");
        assertEquals("deepseek-flash", models.get(0).get("id").asText());
        assertEquals("deepseek", models.get(0).get("provider").asText());
        assertEquals("deepseek-v4-pro", models.get(1).get("id").asText());
        assertEquals("grok-4.7", models.get(2).get("id").asText());
        assertEquals("grok", models.get(2).get("provider").asText());
        assertEquals(3, models.size());
        String again = UpstreamRewriter.rewrite(HttpMethod.GET, "/api/models", body, null);
        assertEquals(3, mapper.readTree(again).get("models").size());
    }

    /**
     * 空目录仍保留 deepseek-flash，并接受 grok-4.7。
     */
    @Test
    void emptyModelListStillKeepsDeepSeekAndGrok() throws Exception {
        JsonNode models = mapper.readTree(UpstreamRewriter.rewrite(HttpMethod.GET, "/api/models", "{\"models\":[]}", null)).get("models");
        assertEquals("deepseek-flash", models.get(0).get("id").asText());
        assertEquals("deepseek", models.get(0).get("provider").asText());
        assertEquals("grok-4.7", models.get(1).get("id").asText());
        assertEquals("grok", models.get(1).get("provider").asText());
    }

    /**
     * 执行详情写上 agentInstanceId 和 agentAddress，覆盖 agent 自己带的同名字段。
     */
    @Test
    void executionDetailCarriesRoutedAgent() throws Exception {
        String body = UpstreamRewriter.rewrite(
                HttpMethod.GET,
                "/api/executions/exec-1",
                "{\"executionId\":\"exec-1\",\"status\":\"RUNNING\",\"agentInstanceId\":\"other\",\"agentAddress\":\"10.0.0.1:1\"}",
                AgentInstance.primary());
        JsonNode node = mapper.readTree(body);
        assertEquals("exec-1", node.get("executionId").asText());
        assertEquals("agent-18731", node.get("agentInstanceId").asText());
        assertEquals("127.0.0.1:18731", node.get("agentAddress").asText());
    }
}
