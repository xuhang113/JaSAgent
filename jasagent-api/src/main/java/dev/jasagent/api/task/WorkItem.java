package dev.jasagent.api.task;

/**
 * 主从调度队列里的一条工作。
 * 相关度先看会话是否相同，再看相关键，最后用目标文本的词元 Jaccard；入队时空白的会话标识按执行标识保存。
 *
 * @param taskId 任务标识，同一调度器里不能重复
 * @param executionId 这条工作所属的执行
 * @param sessionId 会话标识；会话相同的工作项相关度最高
 * @param correlationKey 跨会话的相关键；会话不同而此键相同则相关度次高
 * @param targetText 会话和相关键都对不上时，用来计算词元 Jaccard 的文本
 * @param workflowId 子代理执行时要点名的流程标识
 * @param input 交给该流程的用户输入
 */
public record WorkItem(
        String taskId,
        String executionId,
        String sessionId,
        String correlationKey,
        String targetText,
        String workflowId,
        String input) {
}
