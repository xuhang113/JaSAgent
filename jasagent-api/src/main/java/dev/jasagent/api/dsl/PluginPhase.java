package dev.jasagent.api.dsl;

/**
 * 插件上下文使用的工作流时机。
 * 推理节点一次工具往返按进入、模型前后、工具前后的顺序经过这些时机，离开节点时再走结束时机。
 */
public enum PluginPhase {
    /**
     * 进入节点。同一节点这一时机只调用一轮。
     */
    ON_NODE_START,
    /**
     * 向模型发请求之前。
     */
    BEFORE_MODEL,
    /**
     * 模型已经返回，工具尚未执行。
     */
    AFTER_MODEL,
    /**
     * 工具调用即将执行。
     */
    BEFORE_TOOL,
    /**
     * 工具调用已经返回。
     */
    AFTER_TOOL,
    /**
     * 离开节点。同一节点这一时机只调用一轮。
     */
    ON_NODE_END
}
