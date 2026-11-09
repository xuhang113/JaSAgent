package dev.jasagent.trace;

/**
 * 执行日志里的事件种类，用来区分受理、调度、模型和终态等不同记录。
 */
public enum TraceEventKind {
    /**
     * 执行已被接入。
     */
    ACCEPTED,
    /**
     * 用户输入或用户侧动作。
     */
    USER,
    /**
     * 用户目录或工作区目录已经解析。
     */
    DIRECTORIES,
    /**
     * 调度器取出或分派了任务。
     */
    SCHEDULE,
    /**
     * 相关任务被合并到同一个子代理。
     */
    MERGE,
    /**
     * 工作流节点开始或结束。
     */
    NODE,
    /**
     * 模型路由选定了任务类型或提供方。
     */
    MODEL_ROUTE,
    /**
     * 检索或重排返回了资料。
     */
    RETRIEVAL,
    /**
     * 通行证被申请或作出了裁决。
     */
    PASS,
    /**
     * 工具被调用或返回了结果。
     */
    TOOL,
    /**
     * 插件被调用。
     */
    PLUGIN,
    /**
     * 上下文被压缩。
     */
    COMPRESSION,
    /**
     * 写下了一条订正。
     */
    CORRECTION,
    /**
     * 执行被打断并挂起。
     */
    INTERRUPT,
    /**
     * 执行从挂起中恢复。
     */
    RESUME,
    /**
     * 终态之后做了评估。
     */
    EVALUATION,
    /**
     * 执行进入终态。
     */
    TERMINAL
}
