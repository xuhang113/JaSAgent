package dev.jasagent.agentpass.rag;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 真正进入检索方法的查询。过滤器拦住的请求不会记在这里，测试用它确认业务没有执行。
 */
public final class SearchLog {
    private final CopyOnWriteArrayList<String> queries = new CopyOnWriteArrayList<>();

    /**
     * 记下一次已经进入业务的查询。
     * 副作用是列表增加一项。
     *
     * @param query 调用方提交的查询文本
     */
    public void record(String query) {
        queries.add(query == null ? "" : query);
    }

    /**
     * 按进入顺序返回已经执行过的查询。
     *
     * @return 查询副本
     */
    public List<String> queries() {
        return List.copyOf(queries);
    }
}
