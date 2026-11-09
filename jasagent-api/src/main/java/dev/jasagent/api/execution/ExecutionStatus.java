package dev.jasagent.api.execution;

/**
 * 通行证和执行查询使用的执行状态。
 * 等待人工批准时停在已挂起；成功、失败、取消和挂起都是可以停住的状态。
 */
public enum ExecutionStatus {
    /**
     * 执行已经受理，尚未成功、失败、取消或挂起。
     */
    ACCEPTED,
    /**
     * 步骤已经走完，这次执行以成功收场。
     */
    SUCCEEDED,
    /**
     * 这次执行因为错误停住，结果不能再当作成功。
     */
    FAILED,
    /**
     * 这次执行被取消，后面的步骤不再跑。
     */
    CANCELLED,
    /**
     * 执行停住。通行证等待批准，或运行中的节点被中止时，使用这个状态。
     */
    SUSPENDED
}
