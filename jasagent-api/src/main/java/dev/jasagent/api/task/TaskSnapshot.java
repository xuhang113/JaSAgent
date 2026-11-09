package dev.jasagent.api.task;

/**
 * 某个代理队列里一条任务在某一时刻的样子。
 * 调度刚返回时，快照里的状态是分派当时的排队状态。
 *
 * @param taskId 任务标识
 * @param agentId 当前持有它的代理标识
 * @param role 持有者是主代理还是子代理
 * @param status 当时的任务状态，例如排队、运行中或已经终态
 * @param item 这条任务对应的工作项
 */
public record TaskSnapshot(String taskId, String agentId, AgentRole role, String status, WorkItem item) {
}
