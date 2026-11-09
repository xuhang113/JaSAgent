package dev.jasagent.api.task;

/**
 * 队列里一个代理扮演的角色。
 * 主代理只做调度，子代理才执行分下来的工作项。
 */
public enum AgentRole {
    /**
     * 主代理。它持有待分派队列，自己不执行工作项。
     */
    MAIN,
    /**
     * 子代理。它接收合并后的工作项，并在自己的队列里串行执行。
     */
    SUB
}
