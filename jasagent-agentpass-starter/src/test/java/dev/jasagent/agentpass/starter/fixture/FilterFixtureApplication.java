package dev.jasagent.agentpass.starter.fixture;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 只用来启动 starter 的自动配置。扫描范围停在本包，避免把自动配置类再扫进来登记第二次。
 */
@SpringBootApplication(scanBasePackages = "dev.jasagent.agentpass.starter.fixture")
public class FilterFixtureApplication {
}
