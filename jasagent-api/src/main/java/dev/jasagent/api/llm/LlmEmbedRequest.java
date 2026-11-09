package dev.jasagent.api.llm;

/**
 * 发给嵌入客户端的一次请求。网关嵌入不可用时，路由会改用本地哈希嵌入并仍带上这个执行编号。
 *
 * @param executionId 这次嵌入所属的执行编号，写入追踪和调用记录
 * @param text 待嵌入的文本；客户端收到 null 时按空串嵌入
 */
public record LlmEmbedRequest(String executionId, String text) {
}
