package dev.jasagent.agentpass.starter;

import dev.jasagent.agentpass.starter.fixture.FilterFixtureApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 确认引入 starter 且没有关闭开关时，过滤器会自动登记并排在最前面。
 */
@SpringBootTest(classes = FilterFixtureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AgentPassAutoConfigurationTest {
    @Autowired
    private AgentPassFilter filter;
    @Autowired
    private FilterRegistrationBean<AgentPassFilter> agentPassFilterRegistration;

    /**
     * 过滤器和它的登记都存在，顺序是最高优先级，业务控制器排在它后面。
     */
    @Test
    void filterIsRegisteredFirst() {
        assertNotNull(filter);
        assertNotNull(agentPassFilterRegistration);
        assertEquals(Ordered.HIGHEST_PRECEDENCE, agentPassFilterRegistration.getOrder());
        assertEquals("agentPassFilter", agentPassFilterRegistration.getFilterName());
    }
}
