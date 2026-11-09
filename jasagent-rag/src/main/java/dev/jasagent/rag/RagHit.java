package dev.jasagent.rag;

/**
 * 一条带着出处和三路分数的检索命中。
 * 质量门会核对这些字段是否齐全、哈希是否对得上正文、重排分是否落在 0 到 1。
 *
 * @param sourceScope 语料范围协议名，只接受 user 或 workspace
 * @param uri 文档在语料目录中的相对路径
 * @param docId 文档标识，建索引时与 uri 相同
 * @param chunkId 带范围前缀的段标识
 * @param start 段在原文中的起始下标，包含该位置
 * @param end 段在原文中的结束下标，不包含该位置
 * @param contentHash 段正文的 SHA-256 十六进制
 * @param content 段正文
 * @param lexical 词法路的名次和 BM25 分；没进该路时名次为 0
 * @param vector 向量路的名次和余弦分；没进该路时名次为 0
 * @param title 标题路的名次和命中词数；没进该路时名次为 0
 * @param fusionScore 三路倒数排名合成的分
 * @param rerankScore 重排器给的相关性，合格命中应在 0 到 1
 */
public record RagHit(
        String sourceScope,
        String uri,
        String docId,
        String chunkId,
        int start,
        int end,
        String contentHash,
        String content,
        PathScore lexical,
        PathScore vector,
        PathScore title,
        double fusionScore,
        double rerankScore) {
}
