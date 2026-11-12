package dev.jasagent.agentpass.starter;

/**
 * 一次核销调用的结果。{@code redeemed} 为真才表示通行证服务完成了第一次核销。
 *
 * @param redeemed 是否核销成功
 * @param report 服务附带的校验报告；调用失败时是不可达报告
 */
public record RemoteRedeem(boolean redeemed, VerifyReport report) {
    /**
     * 构造一次没有到达通行证服务的核销结果。
     *
     * @return 未核销且报告为不可达
     */
    public static RemoteRedeem unreachable() {
        return new RemoteRedeem(false, VerifyReport.unreachable());
    }
}
