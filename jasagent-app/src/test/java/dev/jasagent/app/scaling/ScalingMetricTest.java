package dev.jasagent.app.scaling;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进行中的会话是扩缩容主指标，CPU 只出现在补充位置。
 */
class ScalingMetricTest {
    /**
     * 指标文本把进行中的会话放在主指标，CPU 只作为补充。
     */
    @Test
    void prometheusPutsSessionsAheadOfCpu() {
        String body = ScalingSignals.prometheus(2, 0.2);
        assertTrue(body.contains(ScalingSignals.PRIMARY_METRIC + " 2"));
        assertTrue(body.contains(ScalingSignals.CPU_SUPPLEMENT_METRIC));
        assertTrue(body.contains("主指标"));
        assertTrue(body.contains("补充"));
        assertTrue(body.indexOf(ScalingSignals.PRIMARY_METRIC + " ") < body.indexOf(ScalingSignals.CPU_SUPPLEMENT_METRIC + " "));
        assertFalse(ScalingSignals.PRIMARY_METRIC.equals(ScalingSignals.CPU_SUPPLEMENT_METRIC));
    }

    /**
     * 指标路径返回主指标和补充 CPU，其它路径继续向后过滤。
     */
    @Test
    void loadPathExposesBothSignals() throws Exception {
        ScalingMetricFilter filter = new ScalingMetricFilter(() -> 4);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ScalingSignals.PATH);
        request.setRequestURI(ScalingSignals.PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();
        filter.doFilter(request, response, (servletRequest, servletResponse) -> chained.set(true));
        assertFalse(chained.get());
        String body = response.getContentAsString();
        assertTrue(body.contains("jasagent_sessions_active 4"));
        assertTrue(body.contains("process_cpu_usage"));
        assertTrue(body.contains("不能当作唯一依据"));
        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/health");
        health.setRequestURI("/health");
        AtomicBoolean healthChained = new AtomicBoolean();
        FilterChain chain = (servletRequest, servletResponse) -> healthChained.set(true);
        filter.doFilter(health, new MockHttpServletResponse(), chain);
        assertTrue(healthChained.get());
    }
}
