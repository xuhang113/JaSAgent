package dev.jasagent.api.workflow;

import dev.jasagent.api.exec.ExecutionStatus;

/**
 * 工作流引擎交回的一次执行结果。
 * 模型调用失败时这里仍是一条结果：状态为失败，错误码为模型错误，而不是空结果。
 *
 * @param executionId 这次执行的标识
 * @param status 跑完或停住时的执行状态
 * @param output 执行产出的正文；失败节点的输出不写进来
 * @param errorCode 失败时的错误码名称；成功结束时为空
 */
public record WorkflowResult(String executionId, ExecutionStatus status, String output, String errorCode) {
}
