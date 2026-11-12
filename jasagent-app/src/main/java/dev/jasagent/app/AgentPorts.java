package dev.jasagent.app;

/**
 * 已经在跑的两台 agent 容器占用的端口。本进程的单测和试验不要绑定它们。
 */
public final class AgentPorts {
    /**
     * 第一台 agent 的默认端口。
     */
    public static final int PRIMARY = 18731;
    /**
     * 第二台 agent 的端口。用环境变量 JASAGENT_PORT 或 --server.port 指定，本进程默认不监听它。
     */
    public static final int SECONDARY = 18732;

    /**
     * 禁止实例化。端口只通过常量使用。
     */
    private AgentPorts() {
    }
}
