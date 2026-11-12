package dev.jasagent.agentpass.starter;

/**
 * 过滤器访问通行证服务的客户端。校验只读；核销才改变证的使用状态。
 * 实现不得把证明文或会话编号写进日志。
 */
public interface AgentPassClient {
    /**
     * 向通行证服务校验证明文和绑定字段。失败时返回无效报告，不抛出带凭证的异常。
     *
     * @param call 本服务声称的租户、用户、会话、执行、目标服务和工具
     * @param pass 证明文
     * @return 校验报告
     */
    VerifyReport verify(PassCall call, String pass);

    /**
     * 请求通行证服务核销。只有第一次且全部匹配时 {@code redeemed} 为真。
     *
     * @param call 本服务声称的绑定字段
     * @param pass 证明文
     * @return 核销结果
     */
    RemoteRedeem redeem(PassCall call, String pass);
}
