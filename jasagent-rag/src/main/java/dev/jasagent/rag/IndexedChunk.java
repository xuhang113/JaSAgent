package dev.jasagent.rag;

/**
 * 索引文件里的一行段，检索时凭它找回正文、出处和范围。
 *
 * @param scope 这段属于用户目录还是工作区目录
 * @param uri 文档在该范围 docs 下的相对路径
 * @param docId 文档标识，建索引时写成和 uri 一样
 * @param chunkId 带范围前缀的段标识
 * @param title 切段时定下的标题
 * @param start 段在原文中的起始下标，包含该位置
 * @param end 段在原文中的结束下标，不包含该位置
 * @param contentHash 段正文的 SHA-256 十六进制
 * @param content 段正文
 */
record IndexedChunk(
        RagScope scope,
        String uri,
        String docId,
        String chunkId,
        String title,
        int start,
        int end,
        String contentHash,
        String content) {
}
