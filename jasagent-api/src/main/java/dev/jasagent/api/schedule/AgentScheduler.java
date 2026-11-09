package dev.jasagent.api.schedule;

import dev.jasagent.api.task.WorkItem;

import java.util.concurrent.CompletionStage;

/**
 * 主代理的队列调度。
 * 工作项先进入主队列；一次调度才按相关度合并，并分给子代理执行。
 */
public interface AgentScheduler {
    /**
     * 把工作项放进主代理队列。
     * 阶段完成只表示已经入队，此时还不会调用子代理执行端口。
     *
     * @param item 要入队的工作项
     * @return 入队完成后结束的阶段
     */
    CompletionStage<Void> enqueue(WorkItem item);

    /**
     * 从主队列头部取出一批，按相关度合并后分给子代理，并启动该跑的子代理。
     * 阶段在子代理跑完之前就完成；主队列为空时取出条数为 0，不调用执行端口。
     *
     * @return 完成后得到本批取出条数和分派快照的阶段
     */
    CompletionStage<ScheduleBatch> scheduleOnce();
}
