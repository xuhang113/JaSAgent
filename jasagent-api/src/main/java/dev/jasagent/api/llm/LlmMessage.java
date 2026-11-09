package dev.jasagent.api.llm;

/**
 * 发给模型客户端的一条对话。客户端按角色把它转成系统、用户或助手消息，工具结果、纠正和摘要会包进用户消息。
 *
 * @param role 消息角色，取值为 system、user、assistant、tool、correction、summary
 * @param text 该角色对应的正文
 */
public record LlmMessage(String role, String text) {
}
