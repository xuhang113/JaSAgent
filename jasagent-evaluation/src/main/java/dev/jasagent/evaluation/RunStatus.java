package dev.jasagent.evaluation;

/**
 * 一次运行在评估前后可能处于的状态。
 */
public enum RunStatus {
    /**
     * 运行已接收，还没有进入终态。
     */
    ACCEPTED,
    /**
     * 运行正常结束。
     */
    SUCCEEDED,
    /**
     * 运行以失败结束。
     */
    FAILED,
    /**
     * 运行被取消。
     */
    CANCELLED,
    /**
     * 运行已挂起，等待外部决定。
     */
    SUSPENDED
}
