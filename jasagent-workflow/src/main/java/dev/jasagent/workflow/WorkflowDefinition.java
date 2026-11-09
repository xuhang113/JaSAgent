package dev.jasagent.workflow;

import java.util.List;

/**
 * 一份已经解析的工作流：标识、规则、技能、令牌预算和按书写顺序排列的节点。
 *
 * @param id 工作流标识
 * @param rules 写进提示的规则
 * @param skills 写进提示的技能
 * @param budgetTokens 这次工作流的令牌预算
 * @param nodes 按文档顺序排列的节点
 */
public record WorkflowDefinition(
        String id,
        List<String> rules,
        List<String> skills,
        int budgetTokens,
        List<NodeSpec> nodes) {

    /**
     * 一个工作流节点的类型，以及只有对应类型才会用到的字段。
     *
     * @param id 节点标识，不能以两个下划线开头
     * @param type 节点类型，例如 react、tool、condition
     * @param maxSteps react 节点允许的最大模型步数
     * @param tools react 节点可以调用的工具名
     * @param grounding 为 true 时核对回答里的数字和引号是否来自依据
     * @param plugins 这个节点各阶段要调用的插件
     * @param pluginFailure 插件失败时的处理；fail 表示中断节点
     * @param tool tool 节点要调用的工具名
     * @param plugin plugin 节点要调用的插件标识
     * @param queryFrom retrieve 节点的查询来源，只允许 input
     * @param topK retrieve 节点最多取回的条数
     * @param scopes retrieve 节点的检索范围
     * @param required 检索为空或评估未通过时，是否让节点失败
     * @param evaluator evaluate 节点使用的评估器名
     * @param compare condition 节点的比较方式
     * @param value condition 节点用来比较的期望值
     * @param whenTrue 条件成立时进入的节点标识
     * @param whenFalse 条件不成立时进入的节点标识
     * @param childWorkflow 子工作流标识
     * @param tasks 交给子工作流的任务文本
     */
    public record NodeSpec(
            String id,
            String type,
            Integer maxSteps,
            List<String> tools,
            Boolean grounding,
            List<String> plugins,
            String pluginFailure,
            String tool,
            String plugin,
            String queryFrom,
            Integer topK,
            List<String> scopes,
            Boolean required,
            String evaluator,
            String compare,
            String value,
            String whenTrue,
            String whenFalse,
            String childWorkflow,
            List<String> tasks) {
    }
}
