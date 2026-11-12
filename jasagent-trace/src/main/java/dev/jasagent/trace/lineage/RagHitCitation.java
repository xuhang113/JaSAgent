package dev.jasagent.trace.lineage;

/**
 * 一条要记成引用的检索命中。
 * 示例 RAG 把 docId 放到 documentId，把 chunkId 放到 fragmentId，service 用 {@link LineageTables#SERVICE_RAG}。
 * 本类型不依赖检索模块，组装层拿到命中后自己填这三个字段。
 *
 * @param service 检索服务；空白时 {@link RequestLineage#recordRagHits} 改用 rag
 * @param documentId 文档标识
 * @param fragmentId 片段标识
 */
public record RagHitCitation(String service, String documentId, String fragmentId) {

    /**
     * 用示例 RAG 的服务名包一条命中。
     * documentId 对应 RAG 的 docId，fragmentId 对应 chunkId。
     *
     * @param documentId 文档标识，不能为空
     * @param fragmentId 片段标识，不能为空
     * @return 服务名为 rag 的命中
     */
    public static RagHitCitation rag(String documentId, String fragmentId) {
        return new RagHitCitation(LineageTables.SERVICE_RAG, documentId, fragmentId);
    }
}
