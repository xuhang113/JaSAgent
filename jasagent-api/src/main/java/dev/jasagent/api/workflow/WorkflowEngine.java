package dev.jasagent.api.workflow;

import java.util.concurrent.CompletionStage;

/**
 * 按流程定义执行工作流。
 * 公共执行方法返回阶段，图还在跑时这个阶段尚未完成。
 */
public interface WorkflowEngine {
    /**
     * 执行请求里的流程。
     * 跑到结束或在中断节点前挂起时，阶段给出执行标识、状态、正文和错误码。
     *
     * @param request 执行标识、会话、用户、工作区、用户输入和流程定义
     * @return 完成后得到执行结果的阶段
     */
    CompletionStage<WorkflowResult> execute(WorkflowRequest request);
}
