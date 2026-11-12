package dev.jasagent.agentpass.server;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 一次性通行证的 HTTP 接口。
 * 领证是 {@code POST /v1/tickets}。校验是 {@code POST /v1/tickets/verify}，只读，不核销。
 * 核销是 {@code POST /v1/tickets/redeem}。请求里的 sessionId 是会话编号，不是登录令牌。
 */
@RestController
public class PassController {
    private final OneTimePassService passes;
    private final AuditLog audit;

    /**
     * 注入签发服务和流水。流水单独注入，是为了查询时不经过会改状态的核销方法。
     *
     * @param passes 领证、校验和核销
     * @param audit 已经记下的流水
     */
    public PassController(OneTimePassService passes, AuditLog audit) {
        this.passes = passes;
        this.audit = audit;
    }

    /**
     * 领一张一次性证。通过则 201，正文里的 ticket 给运行时放进请求头；人证或策略不通过则 403，没有 ticket。
     *
     * @param request 租户、用户、会话编号、执行、目标和工具名
     * @return 签发正文或拒绝正文
     */
    @PostMapping(TicketPaths.ISSUE)
    public ResponseEntity<?> issue(@RequestBody IssuePassRequest request) {
        OneTimePassService.IssueResult result = passes.issue(request);
        if (!result.issued()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result.denial());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.pass());
    }

    /**
     * 校验证明文。路径是 {@code POST /v1/tickets/verify}。始终返回 200 的报告，说明证明、已用、过期、用户和目标是否匹配，不核销。
     *
     * @param request 证明文和声称的绑定字段
     * @return 只读校验报告
     */
    @PostMapping(TicketPaths.VERIFY)
    public VerifyReport verify(@RequestBody PassProofRequest request) {
        return passes.verify(request);
    }

    /**
     * 核销一张证。路径是 {@code POST /v1/tickets/redeem}。第一次成功返回 200，再次使用返回 409，证明无效、过期或不匹配返回 403。
     *
     * @param request 证明文和声称的绑定字段
     * @return 带状态码的核销结果
     */
    @PostMapping(TicketPaths.REDEEM)
    public ResponseEntity<RedeemResponse> redeem(@RequestBody PassProofRequest request) {
        OneTimePassService.RedeemResult result = passes.redeem(request);
        HttpStatus status = switch (result.status()) {
            case REDEEMED -> HttpStatus.OK;
            case REPLAY -> HttpStatus.CONFLICT;
            case REJECTED -> HttpStatus.FORBIDDEN;
        };
        return ResponseEntity.status(status).body(result.body());
    }

    /**
     * 按写入顺序返回领证、核销、拒绝和重放流水。
     *
     * @return 当前进程里的全部流水
     */
    @GetMapping(TicketPaths.AUDIT)
    public List<AuditEvent> audit() {
        return audit.all();
    }
}
