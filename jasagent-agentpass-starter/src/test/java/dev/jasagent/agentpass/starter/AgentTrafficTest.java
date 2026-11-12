package dev.jasagent.agentpass.starter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对普通流量、豁免路径和代理流量的分类。规则写在 {@link AgentTraffic} 上，这里把每一条边界固定下来。
 */
class AgentTrafficTest {
    /**
     * 缺省前缀、豁免路径、通行证地址和租户要和写给调用方的规则一致。
     */
    @Test
    void defaultsMatchTheWrittenRule() {
        AgentPassFilterProperties properties = new AgentPassFilterProperties();
        assertEquals("/agent/", properties.getAgentPathPrefix());
        assertEquals(List.of("/health"), properties.excludePathList());
        assertEquals("http://127.0.0.1:18741", properties.getClientBaseUrl());
        assertEquals("local", properties.getTenant());
        assertEquals(3000, properties.getClientTimeoutMillis());
    }

    /**
     * {@code /health} 精确豁免，带上代理头或通行证头也不改类。{@code /healthcheck} 不在名单里。
     */
    @Test
    void healthIsExemptEvenWithAgentHeaders() {
        assertEquals(AgentTraffic.Kind.EXEMPT, classify("/health", "true", true));
        assertEquals(AgentTraffic.Kind.EXEMPT, classify("/health", null, false));
        assertEquals(AgentTraffic.Kind.ORDINARY, classify("/healthcheck", null, false));
    }

    /**
     * 没有代理前缀、没有通行证头、代理头也不是代理标记时，请求是普通流量。
     */
    @Test
    void ordinaryTrafficNeedsNoPass() {
        assertEquals(AgentTraffic.Kind.ORDINARY, classify("/notes", null, false));
        assertEquals(AgentTraffic.Kind.ORDINARY, classify("/notes", "", false));
        assertEquals(AgentTraffic.Kind.ORDINARY, classify("/notes", "false", false));
        assertEquals(AgentTraffic.Kind.ORDINARY, classify("/notes", "0", false));
        assertEquals(AgentTraffic.Kind.ORDINARY, classify("/notes", "no", false));
        assertEquals(AgentTraffic.Kind.ORDINARY, classify("/agent", null, false));
        assertEquals(AgentTraffic.Kind.ORDINARY, classify("/agentive", null, false));
    }

    /**
     * 路径命中 {@code /agent/}、出现通行证头，或代理头无法当成“不是代理”时，都是代理流量。
     */
    @Test
    void agentTrafficRequiresAPass() {
        assertEquals(AgentTraffic.Kind.AGENT, classify("/agent/rag/search", null, false));
        assertEquals(AgentTraffic.Kind.AGENT, AgentTraffic.classify("/search", null, false, "/search", List.of("/health")));
        assertEquals(AgentTraffic.Kind.EXEMPT, AgentTraffic.classify("/health", null, false, "/search", List.of("/health")));
        assertEquals(AgentTraffic.Kind.AGENT, classify("/notes", "false", true));
        assertEquals(AgentTraffic.Kind.AGENT, classify("/notes", "true", false));
        assertEquals(AgentTraffic.Kind.AGENT, classify("/notes", "yes", false));
        assertEquals(AgentTraffic.Kind.AGENT, classify("/notes", "1", false));
        assertEquals(AgentTraffic.Kind.AGENT, classify("/notes", "maybe", false));
        assertTrue(AgentTraffic.markedAgent("TRUE"));
        assertFalse(AgentTraffic.markedAgent("false"));
    }

    /**
     * 用缺省前缀和只含 {@code /health} 的豁免名单分类。
     *
     * @param path 请求路径
     * @param agentHeader 代理头
     * @param passHeaderPresent 是否出现通行证头
     * @return 分类结果
     */
    private static AgentTraffic.Kind classify(String path, String agentHeader, boolean passHeaderPresent) {
        return AgentTraffic.classify(path, agentHeader, passHeaderPresent, "/agent/", List.of("/health"));
    }
}
