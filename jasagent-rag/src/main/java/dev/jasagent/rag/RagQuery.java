package dev.jasagent.rag;

import java.util.Set;

/**
 * 一次检索要查的词、目录范围和返回约束。
 *
 * @param text 查询原文；空白查询不会召回段
 * @param scopes 要读的语料范围；空集合或空引用表示用户和工作区都查
 * @param topK 质量门之后最多留下的条数；空引用表示不截断
 * @param required 为零命中时，结果是否标记成失败
 */
public record RagQuery(String text, Set<RagScope> scopes, Integer topK, boolean required) {
}
