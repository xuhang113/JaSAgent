package dev.jasagent.agentpass.rag;

/**
 * 一条示例检索命中。
 *
 * @param source 资料名
 * @param text 命中的正文
 * @param score 相对查询的分数，越大越靠前
 */
public record Hit(String source, String text, int score) {
}
