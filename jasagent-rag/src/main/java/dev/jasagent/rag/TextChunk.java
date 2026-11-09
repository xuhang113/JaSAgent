package dev.jasagent.rag;

/**
 * 一篇文档里切出来的一段原文。
 *
 * @param uri 文档在语料目录中的相对路径
 * @param title 这一段所属的标题；没有标题时用文件名
 * @param chunkId 段在该文档内的标识，形如路径加井号和序号
 * @param start 段在原文中的起始下标，包含该位置
 * @param end 段在原文中的结束下标，不包含该位置
 * @param content 下标区间内的原文，不做改写
 */
public record TextChunk(String uri, String title, String chunkId, int start, int end, String content) {
}
