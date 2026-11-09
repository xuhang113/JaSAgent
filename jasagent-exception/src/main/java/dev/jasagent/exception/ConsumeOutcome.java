package dev.jasagent.exception;

/**
 * 一次任务消费的结局，用来区分动作已完成和动作失败但进程仍可继续。
 */
public enum ConsumeOutcome {
    /**
     * 任务动作正常返回，没有进入失败处理。
     */
    COMPLETED,
    /**
     * 任务动作失败，失败原因已交给失败槽；消费本身不再向外抛出。
     */
    FAILED
}
