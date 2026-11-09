package dev.jasagent.api.dsl;

import java.util.List;

/**
 * 一份可执行的工作流文档。
 * 规则和技能进入模型的系统提示，节点按图上的后继和分支执行。
 *
 * @param id 流程标识
 * @param rules 放进系统提示的规则标识
 * @param skills 放进系统提示的技能标识
 * @param budgetTokens 这次上下文允许的 token 预算
 * @param nodes 文档中的节点
 */
public record WorkflowDefinition(String id, List<String> rules, List<String> skills, int budgetTokens, List<WorkflowNode> nodes) {
}
