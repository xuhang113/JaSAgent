package dev.jasagent.api.dsl;

/**
 * 工作流节点的种类。
 * 引擎按种类决定是推理、调用工具、检索、分支、停下还是结束。
 */
public enum NodeType {
    /**
     * 多步推理。按 {@code maxSteps} 向模型请求，同一轮里的多个工具调用并行。
     */
    REACT,
    /**
     * 调用 {@code tool} 点名的那一个工具。
     */
    TOOL,
    /**
     * 调用 {@code pluginId} 点名的插件，时机只走进入节点和离开节点。
     */
    PLUGIN,
    /**
     * 把工作交给子执行，父流程等子执行的正文回来再继续。
     */
    SUBAGENTS,
    /**
     * 用 {@code evaluator} 点名的评估器检查当前输出。
     */
    EVALUATE,
    /**
     * 在进入该节点之前停下，执行状态变为已挂起，之后用原来的执行标识恢复。
     */
    INTERRUPT,
    /**
     * 按 {@code compare} 的结果走向 {@code thenNode} 或 {@code elseNode}。
     */
    CONDITION,
    /**
     * 用当前用户输入检索，并带上 {@code topK} 和 {@code scopes}。
     */
    RETRIEVE,
    /**
     * 正常结束。走到这里且没有失败时，执行状态为成功。
     */
    END
}
