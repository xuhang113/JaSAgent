package dev.jasagent.agentpass.starter;

import dev.jasagent.agentpass.starter.fixture.FilterFixtureApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式关闭过滤器开关时，starter 不登记过滤器。这个开关不是进程内通行证的 enabled。
 */
@SpringBootTest(classes = FilterFixtureApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "jasagent.agentpass.filter.enabled=false")
class AgentPassFilterDisabledTest {
    @Autowired
    private ApplicationContext context;

    /**
     * 容器里找不到通行证过滤器。
     */
    @Test
    void filterBeanIsAbsent() {
        assertTrue(context.getBeansOfType(AgentPassFilter.class).isEmpty());
    }
}
