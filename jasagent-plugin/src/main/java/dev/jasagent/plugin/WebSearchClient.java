package dev.jasagent.plugin;

import java.util.List;

/**
 * 按查询词取回网页检索命中。
 * 工具本身不关心结果来自本地示例还是真实搜索服务。
 */
public interface WebSearchClient {
    /**
     * 检索查询词并返回命中。
     *
     * @param query 用户给出的查询词
     * @return 标题、链接和摘要；没有结果时可以是空列表
     */
    List<SearchHit> search(String query);
}
