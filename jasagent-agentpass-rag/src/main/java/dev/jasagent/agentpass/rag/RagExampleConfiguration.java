package dev.jasagent.agentpass.rag;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 示例检索需要的进程内对象。通行证过滤器由 starter 自动登记，这里不再注册。
 */
@Configuration
public class RagExampleConfiguration {
    /**
     * 创建检索执行记录，供控制器写入、测试读取。
     *
     * @return 空的查询记录
     */
    @Bean
    public SearchLog searchLog() {
        return new SearchLog();
    }
}
