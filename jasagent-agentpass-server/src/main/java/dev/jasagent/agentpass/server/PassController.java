package dev.jasagent.agentpass.server;

import dev.jasagent.agentpass.durable.AgentPassLedger;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 一次性通行证的 HTTP 接口。
 * 领证是 {@code POST /v1/tickets}。校验是 {@code POST /v1/tickets/verify}，只读，不核销。
 * 核销是 {@code POST /v1/tickets/redeem}。请求里的 sessionId 是会话编号，不是登录令牌。
 */
@RestController
public class PassController {
    /**
     * 与网关相同的登录 Cookie 名。审计只接受这个 Cookie 或 Authorization Bearer，不读查询参数。
     */
    static final String SESSION_COOKIE = "JASAGENT_SESSION";

    private final OneTimePassService passes;
    private final AuditLog audit;
    private final AgentPassLedger ledger;

    /**
     * 注入签发服务、流水和账本。流水按调用方的登录令牌找回租户后再读。
     *
     * @param passes 领证、校验和核销
     * @param audit 已经记下的流水
     * @param ledger 用来按登录令牌找回租户的账本
     */
    public PassController(OneTimePassService passes, AuditLog audit, AgentPassLedger ledger) {
        this.passes = passes;
        this.audit = audit;
        this.ledger = ledger;
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
     * 按写入顺序返回调用方自己租户的流水。没有登录令牌或令牌对不上时返回 401，正文里没有流水。
     *
     * @param request 当前 HTTP 请求，令牌在 Authorization 或登录 Cookie 里
     * @return 该租户的流水，或未登录错误
     */
    @GetMapping(TicketPaths.AUDIT)
    public ResponseEntity<?> audit(HttpServletRequest request) {
        AgentPassLedger.Identity caller = ledger.authenticate(token(request));
        if (caller == null || caller.tenantId() == null || caller.tenantId().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        List<AuditEvent> events = audit.forTenant(caller.tenantId());
        return ResponseEntity.ok(events);
    }

    /**
     * 从 Authorization Bearer 或登录 Cookie 取出令牌原文。不接受查询参数，也不把原文写进日志。
     *
     * @param request 当前请求
     * @return 令牌原文；没有时为空
     */
    private static String token(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String value = header.substring(7).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue().trim();
            }
        }
        return null;
    }
}
