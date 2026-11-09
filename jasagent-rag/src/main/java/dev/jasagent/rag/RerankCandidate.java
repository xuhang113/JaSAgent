package dev.jasagent.rag;

/**
 * 送给重排器的一段候选，只带判断相关性需要的文本和出处。
 *
 * @param chunkId 段标识，重排后用来找回索引行
 * @param content 段正文
 * @param title 段标题
 * @param uri 文档相对路径
 * @param sourceScope 语料范围的协议名，user 或 workspace
 */
public record RerankCandidate(String chunkId, String content, String title, String uri, String sourceScope) {
}
