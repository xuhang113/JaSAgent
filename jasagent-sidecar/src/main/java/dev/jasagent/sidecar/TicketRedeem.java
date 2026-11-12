package dev.jasagent.sidecar;

/**
 * 一次核销调用的结果。只有 {@code redeemed} 为真才表示通行证服务完成了第一次核销。
 * 成功响应里的报告常常把 {@code used} 标成真，不能再拿 {@link TicketVerdict#admissible()} 来判断这次核销。
 *
 * @param redeemed 是否核销成功
 * @param verdict 服务附带的校验报告；调用失败时是不可达报告
 */
public record TicketRedeem(boolean redeemed, TicketVerdict verdict) {
    /**
     * 构造一次没有到达通行证服务的核销结果。
     *
     * @return 未核销且报告为不可达
     */
    public static TicketRedeem unreachable() {
        return new TicketRedeem(false, TicketVerdict.unreachable());
    }
}
