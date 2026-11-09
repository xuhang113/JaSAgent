package dev.jasagent.api.schedule;

import dev.jasagent.api.task.WorkItem;

import java.util.concurrent.CompletionStage;

/**
 * 子代理执行一条工作项的端口。
 * 调度器只负责入队和分派，真正跑工作项时调用这里，并且不等它结束。
 */
public interface SubAgentRunner {
    /**
     * 执行分派给子代理的一条工作项。
     * 阶段正常完成表示这条成功；阶段以异常结束表示这条失败，同一条子队列的下一条仍会继续。
     *
     * @param item 要执行的工作项
     * @return 这条工作项执行结束后完成的阶段
     */
    CompletionStage<Void> run(WorkItem item);
}
