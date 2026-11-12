package dev.jasagent.trace.lineage;

/**
 * 已经落库的一条引用。
 * 按 executionId 查回后，可以用 answerId 把片段追到这次回答，用 service、documentId、fragmentId 对上检索命中。
 *
 * @param citationId 引用行标识。同一命中再记一次时返回原来的标识
 * @param executionId 所属执行
 * @param traceId 与这次请求相同的追踪标识；请求还没记下时可能是空串
 * @param sessionId 会话
 * @param answerId 引用该片段的回答
 * @param service 检索服务
 * @param documentId 文档标识
 * @param fragmentId 片段标识
 */
public record CitationTrace(
        String citationId,
        String executionId,
        String traceId,
        String sessionId,
        String answerId,
        String service,
        String documentId,
        String fragmentId) {
}
