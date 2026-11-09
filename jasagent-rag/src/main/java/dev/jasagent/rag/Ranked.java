package dev.jasagent.rag;

/**
 * 一条召回路上的一段及其原始分。
 *
 * @param chunkId 段标识
 * @param score 该路算出的分，词法路是 BM25，向量路是余弦，标题路是命中词数
 */
record Ranked(String chunkId, double score) {
}
