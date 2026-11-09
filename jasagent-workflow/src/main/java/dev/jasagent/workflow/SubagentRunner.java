package dev.jasagent.workflow;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 跑一个子工作流，并把结果文本交回父图。
 */
public interface SubagentRunner {
    /**
     * 执行一次子工作流调用。
     *
     * @param call 执行编号、子工作流标识、工作流 JSON 和任务列表
     * @return 完成后给出子工作流结果文本的阶段
     */
    CompletionStage<String> run(SubagentCall call);

    /**
     * 一次子工作流调用要带上的执行、子流程和任务。
     *
     * @param executionId 父执行的编号
     * @param childWorkflowId 子工作流标识；空串表示使用默认的单步 react 文档
     * @param workflowJson 没有子工作流标识时使用的工作流 JSON
     * @param tasks 交给子工作流的任务文本
     */
    record SubagentCall(String executionId, String childWorkflowId, String workflowJson, List<String> tasks) {
    }
}
