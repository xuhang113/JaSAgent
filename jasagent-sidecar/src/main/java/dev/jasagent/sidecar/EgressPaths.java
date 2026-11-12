package dev.jasagent.sidecar;

/**
 * sidecar 和通行证服务使用的 HTTP 路径。改路径时要和已有 agentpass 的 {@code /v1/tickets} 保持一致。
 */
public final class EgressPaths {
    /**
     * 存活探针。不访问 agentpass，也不转发。进程内工具不会打到这里。
     */
    public static final String HEALTH = "/health";
    /**
     * 出站转发入口。目的地址放在 {@code X-Jasagent-Url}，方法沿用这次请求的方法。
     */
    public static final String FORWARD = "/v1/forward";
    /**
     * 已有 agentpass 的只读校验。{@code POST /v1/tickets/verify}，不核销。
     */
    public static final String VERIFY = "/v1/tickets/verify";
    /**
     * 已有 agentpass 的核销。{@code POST /v1/tickets/redeem}，同一张证只有第一次成功。
     */
    public static final String REDEEM = "/v1/tickets/redeem";

    /**
     * 禁止实例化。路径只通过常量使用。
     */
    private EgressPaths() {
    }
}
