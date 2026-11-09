package dev.jasagent.controller;

/** 健康检查用来询问存储现在能不能连上。失败应返回假，而不是抛给健康检查。 */
public interface StorageStatus {
    /**
     * 探测存储是否可达。
     *
     * @return 能连上时为真，失败时为假
     */
    boolean reachable();
}
