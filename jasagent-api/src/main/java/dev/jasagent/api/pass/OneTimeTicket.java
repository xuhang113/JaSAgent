package dev.jasagent.api.pass;

/**
 * agentpass 发回的一次性证。空白证视为无效，调用方不得发出外部请求。
 *
 * @param ticket 证文本；空白表示没有领到
 */
public record OneTimeTicket(String ticket) {
    /**
     * 空白证收成空串，避免调用方再判断 null。
     *
     * @param ticket 服务返回的证，可以是 null
     */
    public OneTimeTicket {
        ticket = ticket == null ? "" : ticket.trim();
    }

    /**
     * 证里是否有非空白内容。
     *
     * @return 有内容时为 true
     */
    public boolean valid() {
        return !ticket.isBlank();
    }
}
