package dev.jasagent.api.pass;

/**
 * 向本机 agentpass 领取一次性证的端口。
 * 实现负责访问 http://127.0.0.1:18741，这里只规定入参和返回值。
 */
public interface OneTimeTicketClient {
    /**
     * 为这一次外部调用领证。
     *
     * @param request 租户、用户、会话、执行、目标和工具名
     * @return 领到的证；服务拒绝或不可达时返回无效证，不抛出含密钥的异常
     */
    OneTimeTicket acquire(TicketRequest request);
}
