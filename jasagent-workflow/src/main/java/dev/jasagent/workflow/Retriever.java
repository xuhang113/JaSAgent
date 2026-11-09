package dev.jasagent.workflow;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 按查询文本从指定范围取回若干条文本命中。
 */
public interface Retriever {
    /**
     * 执行一次检索。
     *
     * @param query 执行编号、查询文本、条数和范围
     * @return 完成后给出命中列表的阶段
     */
    CompletionStage<List<RetrievalHit>> retrieve(RetrievalQuery query);

    /**
     * 一次检索要带上的执行编号、查询、条数和范围。
     *
     * @param executionId 这次工作流执行的编号
     * @param query 检索用的文本
     * @param topK 最多返回的条数
     * @param scopes 检索范围，例如用户或工作区
     */
    record RetrievalQuery(String executionId, String query, int topK, List<String> scopes) {
    }

    /**
     * 一条检索命中。
     *
     * @param content 命中正文
     */
    record RetrievalHit(String content) {
    }
}
