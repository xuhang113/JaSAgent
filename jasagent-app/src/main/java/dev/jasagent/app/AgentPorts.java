package dev.jasagent.app;

/**
 * 本机 agent 的约定端口。网关占用 18721，本进程默认监听 18731，另一台用 18732。
 * 单测和试验不要绑定这两个端口。
 */
public final class AgentPorts {
    /**
     * 第一台 agent 的默认端口。本进程不带参数启动时就听这里。
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
