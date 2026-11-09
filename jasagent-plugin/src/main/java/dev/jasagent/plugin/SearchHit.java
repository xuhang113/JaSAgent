package dev.jasagent.plugin;

/**
 * 一条网页检索命中，渲染时各占一行。
 *
 * @param title 结果标题
 * @param url 结果链接
 * @param summary 给模型看的短摘要
 */
public record SearchHit(String title, String url, String summary) {
}
