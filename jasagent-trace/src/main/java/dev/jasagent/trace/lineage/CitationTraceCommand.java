package dev.jasagent.trace.lineage;

/**
 * 要记下的一条引用。
 * 模型回答用到检索片段时，把服务、文档、片段、会话和这次执行一起交给 {@link RequestLineage#recordCitation}。
 * traceId 空着时，如果这次执行已经有请求行，就抄那一行的 traceId；还没有就先存空串，等请求记下后再补上。
 *
 * @param executionId 这次回答所属的执行
 * @param sessionId 会话
 * @param answerId 引用该片段的回答标识
 * @param service 检索服务名，示例 RAG 用 {@link LineageTables#SERVICE_RAG}
 * @param documentId 文档标识，RAG 命中的 docId 放这里
 * @param fragmentId 片段标识，RAG 命中的 chunkId 放这里
 * @param traceId 已有追踪标识；没有就传 null 或空白
 */
public record CitationTraceCommand(
        String executionId,
        String sessionId,
        String answerId,
        String service,
        String documentId,
        String fragmentId,
        String traceId) {
}
