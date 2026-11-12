package dev.jasagent.sidecar;

/**
 * sidecar 访问已有 agentpass 的端口。校验只读；核销才改变证的使用状态。
 * 实现不得把证明文、会话编号或上游密钥写进日志。
 */
public interface TicketOffice {
    /**
     * 调用 {@code POST /v1/tickets/verify}。失败时返回无效报告，不抛出带证明文的异常。
     *
     * @param call 这次出站的租户、用户、会话、执行、规范地址和工具名
     * @param ticket 证明文，只放在请求体里，不放进日志
     * @return 校验报告；服务不可达时原因是 {@code unreachable}
     */
    TicketVerdict verify(TicketCall call, String ticket);

    /**
     * 调用 {@code POST /v1/tickets/redeem}。只有第一次且全部匹配时 {@code redeemed} 为真。
     *
     * @param call 和校验时相同的绑定字段
     * @param ticket 证明文，只放在请求体里，不放进日志
     * @return 核销结果；服务不可达时未核销
     */
    TicketRedeem redeem(TicketCall call, String ticket);
}
