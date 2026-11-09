package dev.jasagent.api.workflow;

import dev.jasagent.api.dsl.WorkflowDefinition;

/**
 * 交给工作流引擎的一次执行请求。
 * 引擎按其中的流程定义跑节点，用户消息作为当前输入。
 *
 * @param executionId 这次执行的标识，恢复时也用它作为线程标识
 * @param sessionId 会话标识，用来关联同一会话里的上下文
 * @param userId 发起执行的用户
 * @param workspace 工作区位置，检查点和日志按它落盘
 * @param input 当前用户消息
 * @param workflow 要执行的流程定义
 */
public record WorkflowRequest(String executionId, String sessionId, String userId, String workspace, String input, WorkflowDefinition workflow) {
}
