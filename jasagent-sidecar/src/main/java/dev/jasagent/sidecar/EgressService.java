package dev.jasagent.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Set;

/**
 * 决定一次出站请求是直接转发、核销后转发，还是拒绝。
 * 进程内时钟和只读工具不进入这里；本服务只处理已经离开 agent 进程的 HTTP。
 * 大模型主机不访问 agentpass。其它主机缺证、伪证、过期、已用或目标不一致时不拨号。
 * 日志只写原因和主机，不写证明文、授权头、查询串和正文。
 */
public final class EgressService {
    private static final Logger log = LoggerFactory.getLogger(EgressService.class);
    /**
     * 允许转发的方法。TRACE 会把请求头回给上游，CONNECT 不在这条 HTTP 入口上处理。
     */
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");

    private final LlmAllowlist allowlist;
    private final TicketOffice office;
    private final UpstreamClient upstream;

    /**
     * 绑定免证名单、通行证端口和上游客户端。构造时不拨号。
     *
     * @param allowlist 大模型主机和额外主机
     * @param office 已有 agentpass 的校验和核销
     * @param upstream 获准之后的转发实现
     */
    public EgressService(LlmAllowlist allowlist, TicketOffice office, UpstreamClient upstream) {
        this.allowlist = allowlist;
        this.office = office;
        this.upstream = upstream;
    }

    /**
     * 处理一次出站。免证主机直接转发，并且不核销随请求带来的证。
     * 其它主机先校验，通过后再核销，只有核销成功才转发。校验未通过时不转发；除了服务不可达，仍会尝试核销以便 agentpass 记下这次使用。
     * 副作用：可能调用通行证服务和上游。拒绝或失败时上游不会收到请求。
     *
     * @param request 目的地址、方法、正文、证明文和绑定字段
     * @return 上游响应或拒绝 JSON。{@code forwarded} 为假时上游没有成功返回
     */
    public EgressOutcome forward(ForwardRequest request) {
        Destination destination = Destination.parse(request.url());
        if (destination == null) {
            log.info("egress rejected reason={} host={}", EgressReasons.BAD_TARGET, "-");
            return EgressOutcome.rejected(EgressReasons.BAD_TARGET);
        }
        if (!METHODS.contains(request.method())) {
            log.info("egress rejected reason={} host={}", EgressReasons.METHOD_NOT_ALLOWED, destination.host());
            return EgressOutcome.rejected(EgressReasons.METHOD_NOT_ALLOWED);
        }
        if (allowlist.permits(destination.host())) {
            log.info("egress forwarded host={} allow=llm", destination.host());
            return send(request, destination);
        }
        if (request.ticket().isBlank()) {
            log.info("egress rejected reason={} host={}", EgressReasons.MISSING_TICKET, destination.host());
            return EgressOutcome.rejected(EgressReasons.MISSING_TICKET);
        }
        TicketCall call = new TicketCall(request.tenantId(), request.userId(), request.sessionId(),
                request.executionId(), destination.canonical(), request.toolName());
        TicketVerdict verdict = verify(call, request.ticket());
        if (!verdict.admissible()) {
            if (!EgressReasons.UNREACHABLE.equals(verdict.reason())) {
                redeemQuietly(call, request.ticket());
            }
            String reason = TicketVerdict.explain(verdict);
            log.info("egress rejected reason={} host={}", reason, destination.host());
            return EgressOutcome.rejected(reason);
        }
        TicketRedeem redeemed = redeem(call, request.ticket());
        if (!redeemed.redeemed()) {
            String reason = TicketVerdict.explain(redeemed.verdict());
            log.info("egress rejected reason={} host={}", reason, destination.host());
            return EgressOutcome.rejected(reason);
        }
        log.info("egress forwarded host={} allow=ticket", destination.host());
        return send(request, destination);
    }

    /**
     * 向通行证服务校验。客户端抛错时当成不可达，不把异常消息写进日志。
     *
     * @param call 绑定字段
     * @param ticket 证明文
     * @return 校验报告；失败时是不可达报告
     */
    private TicketVerdict verify(TicketCall call, String ticket) {
        try {
            TicketVerdict verdict = office.verify(call, ticket);
            return verdict == null ? TicketVerdict.unreachable() : verdict;
        } catch (RuntimeException ex) {
            log.info("agentpass verify failed kind={}", ex.getClass().getSimpleName());
            return TicketVerdict.unreachable();
        }
    }

    /**
     * 向通行证服务核销。客户端抛错时当成没有核销成功。
     *
     * @param call 绑定字段
     * @param ticket 证明文
     * @return 核销结果；失败时未核销
     */
    private TicketRedeem redeem(TicketCall call, String ticket) {
        try {
            TicketRedeem redeemed = office.redeem(call, ticket);
            return redeemed == null ? TicketRedeem.unreachable() : redeemed;
        } catch (RuntimeException ex) {
            log.info("agentpass redeem failed kind={}", ex.getClass().getSimpleName());
            return TicketRedeem.unreachable();
        }
    }

    /**
     * 校验未通过时补一次核销，让 agentpass 有机会记下伪证、过期或目标不匹配。
     * 这次核销的结果不改变拒绝决定。服务不可达时不要调用本方法。
     *
     * @param call 绑定字段
     * @param ticket 证明文
     */
    private void redeemQuietly(TicketCall call, String ticket) {
        try {
            office.redeem(call, ticket);
        } catch (RuntimeException ex) {
            log.info("agentpass redeem failed kind={}", ex.getClass().getSimpleName());
        }
    }

    /**
     * 把已经获准的请求送到上游。头在这里滤掉通行证。连接失败变成 502，不附带异常原文。
     *
     * @param request 原始出站请求
     * @param destination 已经解析的目的地
     * @return 上游结果，或未转发的失败结果
     */
    private EgressOutcome send(ForwardRequest request, Destination destination) {
        try {
            UpstreamResponse response = upstream.exchange(request.method(), URI.create(destination.canonical()),
                    UpstreamHeaders.outbound(request.headers()), request.body());
            if (response == null || !response.reached()) {
                log.info("egress upstream failed host={}", destination.host());
                return EgressOutcome.rejected(EgressReasons.UPSTREAM_FAILED);
            }
            return EgressOutcome.forwarded(response.status(), response.contentType(), response.body());
        } catch (RuntimeException ex) {
            log.info("egress upstream failed host={} kind={}", destination.host(), ex.getClass().getSimpleName());
            return EgressOutcome.rejected(EgressReasons.UPSTREAM_FAILED);
        }
    }
}
