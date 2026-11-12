package dev.jasagent.agentpass.server;

/**
 * 一次性证的 HTTP 路径。领证固定是 {@code POST /v1/tickets}，运行时按这个地址提交。
 * 校验不核销，核销才把证标成已用。
 */
public final class TicketPaths {
    /**
     * 领证。{@code POST http://127.0.0.1:18741/v1/tickets}。
     * 请求字段是 tenantId、userId、sessionId、executionId、target、toolName。成功时正文含 ticket。
     */
    public static final String ISSUE = "/v1/tickets";

    /**
     * 校验。{@code POST http://127.0.0.1:18741/v1/tickets/verify}。
     * 只报告证明文、是否已用、是否过期、用户和目标是否匹配，不核销。
     */
    public static final String VERIFY = "/v1/tickets/verify";

    /**
     * 核销。{@code POST http://127.0.0.1:18741/v1/tickets/redeem}。
     * 第一次成功后，同一张证再来就是重放。
     */
    public static final String REDEEM = "/v1/tickets/redeem";

    /**
     * 流水。{@code GET http://127.0.0.1:18741/v1/tickets/audit}。不含登录令牌和证明文。
     */
    public static final String AUDIT = "/v1/tickets/audit";

    /**
     * 禁止实例化。路径只通过常量使用。
     */
    private TicketPaths() {
    }
}
