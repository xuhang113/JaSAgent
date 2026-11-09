package dev.jasagent.api.dsl;

import java.util.List;

/**
 * 工作流图上的一个节点。
 * 哪些字段参与执行取决于节点种类：步数约束推理节点，比较和分支约束条件节点，条数和范围约束检索节点。
 *
 * @param id 节点标识
 * @param type 节点种类
 * @param next 直线后继的节点标识；条件节点改走两个分支
 * @param maxSteps 推理节点允许的模型请求次数，用尽后这次执行失败
 * @param tools 推理节点可以调用的工具名
 * @param grounding 推理节点是否在模型返回后做落地核对
 * @param plugins 这个节点要按时机调用的插件标识
 * @param pluginFailure 插件抛错时的策略；{@code fail} 让节点失败，缺省则记下异常后继续
 * @param compare 条件节点的比较方式，例如布尔、相等或数值比较
 * @param left 条件比较读取的左值
 * @param right 条件比较读取的右值
 * @param thenNode 比较成立时进入的节点标识
 * @param elseNode 比较不成立时进入的节点标识
 * @param queryFrom 检索查询从哪取；检索节点只接受当前用户输入
 * @param topK 检索节点在重排后保留的条数
 * @param scopes 检索节点要读的范围名称，例如用户目录和工作区
 * @param required 为真时，检索为空或评估未通过会使这次执行失败
 * @param evaluator 评估节点要点名的评估器标识
 * @param childWorkflow 子代理节点交给子执行的流程标识；缺省时子执行只有一个推理节点
 * @param tool 工具节点要点名的工具名
 * @param pluginId 插件节点要点名的插件标识
 */
public record WorkflowNode(
        String id,
        NodeType type,
        String next,
        Integer maxSteps,
        List<String> tools,
        Boolean grounding,
        List<String> plugins,
        String pluginFailure,
        String compare,
        String left,
        String right,
        String thenNode,
        String elseNode,
        String queryFrom,
        Integer topK,
        List<String> scopes,
        Boolean required,
        String evaluator,
        String childWorkflow,
        String tool,
        String pluginId) {
}
