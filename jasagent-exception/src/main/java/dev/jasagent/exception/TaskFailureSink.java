package dev.jasagent.exception;

import dev.jasagent.api.exception.AgentException;

/**
 * 接收任务消费失败的通知，让调用方把这条任务标成失败而不是让进程退出。
 */
public interface TaskFailureSink {
    /**
     * 记下某次执行中的任务已经失败。副作用由实现决定，通常是写失败状态；本接口不吞掉实现抛出的运行时异常。
     *
     * @param executionId 这次执行的标识
     * @param taskId 失败任务的标识
     * @param error 已经带上执行标识的失败原因
     */
    void markFailed(String executionId, String taskId, AgentException error);
}
