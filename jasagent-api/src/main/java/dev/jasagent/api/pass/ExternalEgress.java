package dev.jasagent.api.pass;

/**
 * 外部工具和 MCP 在真正发出调用前的放行判断。
 * 进程内通行证已经是否决或等待时，这里不领证也不调用。时钟和只读工具不领证。
 * 允许或跳过的外部调用必须先拿到有效一次性证。
 */
public final class ExternalEgress {
    /**
     * 示例检索工具所在的地址。领证时的目标，以及真正发起检索时的基址，都用这一处。
     */
    public static final String RAG_TARGET = "http://127.0.0.1:18751";
    /**
     * 本机 agentpass 领取一次性证的地址。请求体带租户、用户、会话、执行、目标和工具名。
     */
    public static final String TICKET_ENDPOINT = "http://127.0.0.1:18741/v1/tickets";
    /**
     * 没有领到有效证时返回给调用方的说明。此时外部请求还没有发出。
     */
    public static final String TICKET_MISSING = "没有有效的一次性证";

    /**
     * 工具要不要离开进程。时钟和只读工具留在进程内，其余外部能力要领证。
     */
    public enum Kind {
        /**
         * 内置时钟，不领证。
         */
        CLOCK,
        /**
         * 已有的进程内只读工具，不领证。
         */
        READ_ONLY,
        /**
         * 要访问进程外的工具或 MCP。
         */
        EXTERNAL
    }

    /**
     * 一次放行判断的结果。
     *
     * @param askedForTicket 是否已经向 agentpass 发过领证请求
     * @param invoke 是否可以执行工具本体或发出外部请求
     * @param detail 不能执行时的说明；可以执行且领到证时是证文本，不领证时是空串
     */
    public record Outcome(boolean askedForTicket, boolean invoke, String detail) {
    }

    /**
     * 禁止实例化。判断只通过 {@link #permit} 完成。
     */
    private ExternalEgress() {
    }

    /**
     * 决定这次调用能不能继续。否决和等待不领证。时钟和只读工具直接允许执行。
     * 外部调用在允许或跳过时领证，证无效则不执行。
     *
     * @param decision 进程内通行证已经做出的决定；时钟路径可以传 null，会按允许处理
     * @param kind 时钟、只读或外部
     * @param client 领证端口；外部调用时不能为空，为空则视为没有有效证
     * @param request 领证所需的身份和目标；非外部调用可以是 null
     * @return 是否领过证、能否执行，以及说明或证文本
     */
    public static Outcome permit(PassDecision decision, Kind kind, OneTimeTicketClient client, TicketRequest request) {
        if (kind == Kind.CLOCK || kind == Kind.READ_ONLY) {
            return new Outcome(false, true, "");
        }
        if (decision == PassDecision.DENY || decision == PassDecision.PENDING || decision == null) {
            String detail = decision == null ? TICKET_MISSING : decision.name();
            return new Outcome(false, false, detail);
        }
        if (decision != PassDecision.ALLOW && decision != PassDecision.SKIPPED) {
            return new Outcome(false, false, decision.name());
        }
        if (client == null || request == null) {
            return new Outcome(false, false, TICKET_MISSING);
        }
        OneTimeTicket ticket = client.acquire(request);
        if (ticket == null || !ticket.valid()) {
            return new Outcome(true, false, TICKET_MISSING);
        }
        return new Outcome(true, true, ticket.ticket());
    }
}
