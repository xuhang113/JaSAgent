package dev.jasagent.agentpass.starter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对过滤器拦截缺证、伪证、过期、已用、用户或目标不匹配，并且普通流量在 agentpass 不可达时仍然放行。
 */
class AgentPassFilterTest {
    private FakeClient client;
    private AgentPassFilter filter;

    /**
     * 每个用例都从一张新的假客户端和指向 rag 的配置开始。
     */
    @BeforeEach
    void setUp() {
        client = new FakeClient();
        AgentPassFilterProperties properties = new AgentPassFilterProperties();
        properties.setServiceName("rag");
        properties.setToolName("rag.search");
        properties.setTenant("local");
        filter = new AgentPassFilter(client, properties);
    }

    /**
     * {@code /health} 即使带了代理头和通行证头也不访问 agentpass，业务链会执行。
     */
    @Test
    void healthPassesWithoutCallingAgentPass() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader(AgentPassHeaders.AGENT, "true");
        request.addHeader(AgentPassHeaders.PASS, "v1.present");
        Hit hit = invoke(request);
        assertTrue(hit.entered);
        assertEquals(0, client.verifies);
        assertEquals(0, client.redeems);
    }

    /**
     * 普通路径不带代理标记时直接放行。客户端即使会抛错也不被调用。
     */
    @Test
    void ordinaryTrafficSkipsAgentPass() throws Exception {
        client.failure = new IllegalStateException("down");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/notes");
        request.addHeader(AgentPassHeaders.AGENT, "false");
        Hit hit = invoke(request);
        assertTrue(hit.entered);
        assertEquals(200, hit.response.getStatus());
        assertEquals(0, client.verifies);
    }

    /**
     * 代理路径没有通行证时拒绝，业务链不执行，也不去问 agentpass。
     */
    @Test
    void missingPassDoesNotEnterBusiness() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/rag/search");
        Hit hit = invoke(request);
        assertFalse(hit.entered);
        assertEquals(401, hit.response.getStatus());
        assertTrue(hit.response.getContentAsString().contains("\"reason\":\"missing-pass\""));
        assertEquals(0, client.verifies);
    }

    /**
     * 通行证头是空字符串时仍算代理流量，按缺证拒绝。
     */
    @Test
    void blankPassHeaderIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/notes");
        request.addHeader(AgentPassHeaders.AGENT, "false");
        request.addHeader(AgentPassHeaders.PASS, "");
        Hit hit = invoke(request);
        assertFalse(hit.entered);
        assertEquals(401, hit.response.getStatus());
        assertTrue(hit.response.getContentAsString().contains("missing-pass"));
    }

    /**
     * 证明文无效时拒绝并核销尝试会记到客户端，业务不执行。
     */
    @Test
    void forgedPassDoesNotEnterBusiness() throws Exception {
        client.report = new VerifyReport(false, false, false, false, false, false, false, false, false, "proof-invalid");
        Hit hit = invoke(agentRequest("v1.forged"));
        assertFalse(hit.entered);
        assertEquals(401, hit.response.getStatus());
        assertTrue(hit.response.getContentAsString().contains("proof-invalid"));
        assertEquals(1, client.verifies);
        assertEquals(1, client.redeems);
    }

    /**
     * 过期的证拒绝，状态是 403，业务不执行。
     */
    @Test
    void expiredPassDoesNotEnterBusiness() throws Exception {
        client.report = report(false, true, true, true);
        Hit hit = invoke(agentRequest("v1.expired"));
        assertFalse(hit.entered);
        assertEquals(403, hit.response.getStatus());
        assertTrue(hit.response.getContentAsString().contains("\"reason\":\"expired\""));
        assertEquals(1, client.redeems);
    }

    /**
     * 已经使用的证拒绝，业务不执行。
     */
    @Test
    void usedPassDoesNotEnterBusiness() throws Exception {
        client.report = report(true, false, true, true);
        Hit hit = invoke(agentRequest("v1.used"));
        assertFalse(hit.entered);
        assertEquals(403, hit.response.getStatus());
        assertTrue(hit.response.getContentAsString().contains("\"reason\":\"used\""));
    }

    /**
     * 用户不匹配时拒绝，业务不执行。
     */
    @Test
    void userMismatchDoesNotEnterBusiness() throws Exception {
        client.report = report(false, false, false, true);
        Hit hit = invoke(agentRequest("v1.user"));
        assertFalse(hit.entered);
        assertEquals(403, hit.response.getStatus());
        assertTrue(hit.response.getContentAsString().contains("user-mismatch"));
    }

    /**
     * 目标不匹配时拒绝，业务不执行。
     */
    @Test
    void targetMismatchDoesNotEnterBusiness() throws Exception {
        client.report = report(false, false, true, false);
        Hit hit = invoke(agentRequest("v1.target"));
        assertFalse(hit.entered);
        assertEquals(403, hit.response.getStatus());
        assertTrue(hit.response.getContentAsString().contains("target-mismatch"));
    }

    /**
     * 校验通过且核销成功后才进入业务。目标服务取配置，不取客户端自报的服务名。
     */
    @Test
    void validPassIsRedeemedThenEntersBusiness() throws Exception {
        client.report = report(false, false, true, true);
        client.redeemOk = true;
        MockHttpServletRequest request = agentRequest("v1.ok");
        request.addHeader("X-Jasagent-Target", "other");
        Hit hit = invoke(request);
        assertTrue(hit.entered);
        assertEquals(1, client.verifies);
        assertEquals(1, client.redeems);
        assertEquals("rag", client.last.targetService());
        assertEquals("alice", client.last.user());
        assertEquals("rag.search", client.last.toolName());
        assertEquals("session-1", client.last.sessionId());
        assertEquals("exec-1", client.last.executionId());
    }

    /**
     * 校验看起来通过但核销没有成功时，仍然不进入业务。
     */
    @Test
    void redeemFailureDoesNotEnterBusiness() throws Exception {
        client.report = report(false, false, true, true);
        client.redeemOk = false;
        Hit hit = invoke(agentRequest("v1.race"));
        assertFalse(hit.entered);
        assertEquals(1, client.redeems);
    }

    /**
     * agentpass 不可达时，代理流量拒绝，普通流量仍然放行。
     */
    @Test
    void unreachableAgentPassRejectsOnlyAgentTraffic() throws Exception {
        client.report = VerifyReport.unreachable();
        Hit agent = invoke(agentRequest("v1.down"));
        assertFalse(agent.entered);
        assertEquals(401, agent.response.getStatus());
        assertTrue(agent.response.getContentAsString().contains("unreachable"));
        assertEquals(0, client.redeems);
        MockHttpServletRequest ordinary = new MockHttpServletRequest("GET", "/notes");
        Hit open = invoke(ordinary);
        assertTrue(open.entered);
    }

    /**
     * 本服务没有配置目标时，带了证也不进入业务，避免把证用到一个未知目标上。
     */
    @Test
    void missingIdentityDoesNotEnterBusiness() throws Exception {
        AgentPassFilterProperties properties = new AgentPassFilterProperties();
        properties.setServiceName("");
        filter = new AgentPassFilter(client, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/rag/search");
        request.addHeader(AgentPassHeaders.TICKET, "v1.anon");
        Hit hit = invoke(request);
        assertFalse(hit.entered);
        assertEquals(401, hit.response.getStatus());
        assertTrue(hit.response.getContentAsString().contains("missing-identity"));
        assertEquals(0, client.verifies);
    }

    /**
     * 运行时只带 {@code X-Jasagent-Ticket}，不带用户头。校验通过并核销后才进入业务。
     */
    @Test
    void ticketHeaderAloneCanEnterAfterRedeem() throws Exception {
        client.report = report(false, false, true, true);
        client.redeemOk = true;
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/search");
        request.addHeader(AgentPassHeaders.TICKET, "v1.runtime");
        Hit hit = invoke(request);
        assertTrue(hit.entered);
        assertEquals(1, client.verifies);
        assertEquals(1, client.redeems);
        assertEquals("rag", client.last.targetService());
        assertEquals("", client.last.user());
    }

    /**
     * 组装一份除了已用、过期、用户和目标之外都匹配的报告。
     *
     * @param used 是否已核销
     * @param expired 是否已过期
     * @param userMatch 用户是否匹配
     * @param targetMatch 目标是否匹配
     * @return 不能同时满足全部放行条件的报告；四项都是“可用”时才是可放行报告
     */
    private static VerifyReport report(boolean used, boolean expired, boolean userMatch, boolean targetMatch) {
        boolean accepted = !used && !expired && userMatch && targetMatch;
        return new VerifyReport(true, used, expired, userMatch, targetMatch, true, true, true, accepted, null);
    }

    /**
     * 造一条带齐身份头的代理请求。
     *
     * @param pass 证明文
     * @return 尚未经过过滤器的请求
     */
    private static MockHttpServletRequest agentRequest(String pass) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/rag/search");
        request.addHeader(AgentPassHeaders.PASS, pass);
        request.addHeader(AgentPassHeaders.USER, "alice");
        request.addHeader(AgentPassHeaders.SESSION, "session-1");
        request.addHeader(AgentPassHeaders.EXECUTION, "exec-1");
        request.addHeader(AgentPassHeaders.TENANT, "local");
        return request;
    }

    /**
     * 执行过滤器，并用标记记下业务链有没有被调用。
     *
     * @param request 请求
     * @return 是否进入业务，以及响应
     */
    private Hit invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean entered = new AtomicBoolean();
        FilterChain chain = (req, res) -> entered.set(true);
        filter.doFilter(request, response, chain);
        return new Hit(entered.get(), response);
    }

    /**
     * 一次过滤器调用的结果。
     *
     * @param entered 业务链是否执行
     * @param response 过滤器写过的响应
     */
    private record Hit(boolean entered, MockHttpServletResponse response) {
    }

    /**
     * 不连真实通行证服务的客户端，按测试事先放好的报告回答。
     */
    private static final class FakeClient implements AgentPassClient {
        private VerifyReport report = VerifyReport.unreachable();
        private boolean redeemOk;
        private int verifies;
        private int redeems;
        private RuntimeException failure;
        private PassCall last;

        /**
         * 记下调用并返回预设报告。预设了失败时抛出，用来模拟客户端故障。
         *
         * @param call 过滤器组装的绑定
         * @param pass 证明文
         * @return 预设报告
         */
        @Override
        public VerifyReport verify(PassCall call, String pass) {
            verifies++;
            last = call;
            if (failure != null) {
                throw failure;
            }
            return report;
        }

        /**
         * 记下核销并按预设返回是否成功。
         *
         * @param call 过滤器组装的绑定
         * @param pass 证明文
         * @return 预设核销结果
         */
        @Override
        public RemoteRedeem redeem(PassCall call, String pass) {
            redeems++;
            last = call;
            if (failure != null) {
                throw failure;
            }
            return new RemoteRedeem(redeemOk, report);
        }
    }
}
