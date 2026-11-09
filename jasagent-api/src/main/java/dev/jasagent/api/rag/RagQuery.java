package dev.jasagent.api.rag;

import java.util.List;

/**
 * 交给检索管道的一次查询。
 * 范围为空时同时查用户目录和工作区里已经建好的索引；给出范围时只查列出的那些。
 *
 * @param executionId 这次检索所属的执行
 * @param userId 用户标识，用来定位用户目录里的索引
 * @param workspace 工作区位置，用来定位工作区里的索引
 * @param query 查询正文
 * @param topK 质量校验之后最多保留的条数
 * @param scopes 要检索的范围；不限定时同时包含用户目录和工作区
 */
public record RagQuery(String executionId, String userId, String workspace, String query, int topK, List<SourceScope> scopes) {
}
