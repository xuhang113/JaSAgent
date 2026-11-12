package dev.jasagent.agentpass.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 签发、校验和核销一次性通行证。校验不改状态；核销只在全部绑定都匹配且尚未使用时成功一次。
 * 拒绝、过期、不匹配和重放都写入流水。登录令牌和证明文不进入流水。
 */
public final class OneTimePassService {
    private final HumanAccess access;
    private final PassSigner signer;
    private final PassStore store;
    private final AuditLog audit;
    private final Clock clock;
    private final Duration ttl;

    /**
     * 组装领证所需的人证核对、签名、存储、流水和时钟。
     *
     * @param access 判断已登录用户能不能访问目标的组件
     * @param signer 签发和打开证明文的签名器
     * @param store 本进程内的证登记
     * @param audit 领证、核销、拒绝和重放的流水
     * @param clock 判断过期和流水时间的时钟
     * @param ttl 从签发时刻起的有效时长，必须为正
     */
    public OneTimePassService(HumanAccess access, PassSigner signer, PassStore store, AuditLog audit, Clock clock,
                               Duration ttl) {
        this.access = access;
        this.signer = signer;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * 核对登录用户和策略。允许则签发一张绑定全部字段的一次性证；否则只记拒绝流水。
     * 副作用：成功时存储加一条未使用记录并追加领证流水；失败时只追加拒绝流水。
     *
     * @param request 领证请求，会话字段是登录令牌
     * @return 签发结果或拒绝结果
     */
    public IssueResult issue(IssuePassRequest request) {
        if (incomplete(request)) {
            audit.append(AuditKind.DENY, null, text(request, IssueField.TENANT), text(request, IssueField.USER),
                    text(request, IssueField.EXECUTION), text(request, IssueField.TARGET), text(request, IssueField.TOOL),
                    PassReasons.INCOMPLETE);
            return IssueResult.denied(PassReasons.INCOMPLETE);
        }
        HumanAccess.Decision decision = access.decide(request);
        if (!decision.allowed()) {
            audit.append(AuditKind.DENY, null, request.tenantId(), request.userId(), request.executionId(),
                    request.target(), request.toolName(), decision.reason());
            return IssueResult.denied(decision.reason());
        }
        String passId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.parse(clock.instant().plus(ttl).toString());
        PassSigner.Claims claims = new PassSigner.Claims(passId, request.tenantId().trim(), request.userId().trim(),
                decision.sessionId(), request.executionId().trim(), request.target().trim(),
                request.toolName().trim(), expiresAt);
        String document = signer.sign(claims);
        store.save(PassState.issued(claims));
        audit.append(AuditKind.ISSUE, passId, claims.tenant(), claims.user(), claims.executionId(),
                claims.targetService(), claims.toolName(), PassReasons.ISSUED);
        return IssueResult.issued(new IssuedPass(true, passId, document, claims.tenant(), claims.user(),
                claims.sessionId(), claims.executionId(), claims.targetService(), claims.toolName(), expiresAt));
    }

    /**
     * 检查证明文、是否已用、是否过期，以及用户和目标是否匹配。不核销，也不写流水。
     *
     * @param request 证明文和调用方声称的绑定字段
     * @return 只读校验报告
     */
    public VerifyReport verify(PassProofRequest request) {
        return inspect(request).report();
    }

    /**
     * 原子核销一张证。已经用过的再来一次记为重放，不改掉第一次核销。
     * 副作用：成功时把证标成已用并追加核销流水；失败时追加拒绝或重放流水。
     *
     * @param request 证明文和调用方声称的绑定字段
     * @return 核销状态和给调用方的响应
     */
    public RedeemResult redeem(PassProofRequest request) {
        Inspection inspection = inspect(request);
        if (!inspection.report().proofValid() || inspection.state() == null) {
            audit.append(AuditKind.DENY, null, field(request, ProofField.TENANT), field(request, ProofField.USER),
                    field(request, ProofField.EXECUTION), field(request, ProofField.TARGET), field(request, ProofField.TOOL),
                    PassReasons.PROOF_INVALID);
            return RedeemResult.rejected(RedeemResponse.from(false, VerifyReport.invalid(PassReasons.PROOF_INVALID),
                    PassReasons.PROOF_INVALID, null));
        }
        String passId = inspection.state().passId();
        Holder holder = new Holder();
        store.compute(passId, (id, current) -> {
            if (current == null || inspection.claims() == null || !current.sameClaims(inspection.claims())) {
                holder.outcome = Outcome.INVALID;
                return current;
            }
            VerifyReport report = reportFor(current, request);
            if (current.used()) {
                holder.outcome = Outcome.REPLAY;
                holder.report = report;
                return current;
            }
            if (!report.accepted()) {
                holder.outcome = Outcome.REJECTED;
                holder.report = report;
                return current;
            }
            holder.outcome = Outcome.REDEEMED;
            holder.report = report;
            return current.redeemedCopy();
        });
        return switch (holder.outcome) {
            case REDEEMED -> {
                audit.append(AuditKind.REDEEM, passId, inspection.state().tenant(), inspection.state().user(),
                        inspection.state().executionId(), inspection.state().targetService(), inspection.state().toolName(),
                        PassReasons.REDEEMED);
                yield RedeemResult.redeemed(RedeemResponse.from(true, holder.report, PassReasons.REDEEMED, passId));
            }
            case REPLAY -> {
                audit.append(AuditKind.REPLAY, passId, inspection.state().tenant(), inspection.state().user(),
                        inspection.state().executionId(), inspection.state().targetService(), inspection.state().toolName(),
                        PassReasons.REPLAY);
                yield RedeemResult.replay(RedeemResponse.from(false, holder.report, PassReasons.REPLAY, passId));
            }
            case REJECTED -> {
                audit.append(AuditKind.DENY, passId, inspection.state().tenant(), inspection.state().user(),
                        inspection.state().executionId(), inspection.state().targetService(), inspection.state().toolName(),
                        holder.report.reason());
                yield RedeemResult.rejected(RedeemResponse.from(false, holder.report, holder.report.reason(), passId));
            }
            case INVALID -> {
                audit.append(AuditKind.DENY, null, field(request, ProofField.TENANT), field(request, ProofField.USER),
                        field(request, ProofField.EXECUTION), field(request, ProofField.TARGET), field(request, ProofField.TOOL),
                        PassReasons.PROOF_INVALID);
                yield RedeemResult.rejected(RedeemResponse.from(false, VerifyReport.invalid(PassReasons.PROOF_INVALID),
                        PassReasons.PROOF_INVALID, null));
            }
        };
    }

    /**
     * 打开证明文并和内存记录、调用方声称的字段对照。找不到记录或声明对不上时证明无效。
     *
     * @param request 校验或核销请求
     * @return 报告以及对应的内存状态；证明无效时状态为空
     */
    private Inspection inspect(PassProofRequest request) {
        if (request == null || request.ticket() == null || request.ticket().isBlank()) {
            return new Inspection(VerifyReport.invalid(PassReasons.PROOF_INVALID), null, null);
        }
        var opened = signer.open(request.ticket());
        if (opened.isEmpty()) {
            return new Inspection(VerifyReport.invalid(PassReasons.PROOF_INVALID), null, null);
        }
        PassSigner.Claims claims = opened.get();
        PassState state = store.find(claims.passId());
        if (state == null || !state.sameClaims(claims)) {
            return new Inspection(VerifyReport.invalid(PassReasons.PROOF_INVALID), null, null);
        }
        return new Inspection(reportFor(state, request), state, claims);
    }

    /**
     * 对照一张已知的证和调用方声称的字段，算出校验报告。不修改证的状态。
     *
     * @param state 内存里的证
     * @param request 调用方声称的绑定
     * @return 含各项布尔判断的报告
     */
    private VerifyReport reportFor(PassState state, PassProofRequest request) {
        boolean expired = !clock.instant().isBefore(state.expiresAt());
        boolean userMatch = agreed(state.user(), field(request, ProofField.USER));
        String expectedTarget = field(request, ProofField.TARGET);
        boolean targetMatch = !blank(expectedTarget) && state.targetService().equals(expectedTarget)
                && agreed(state.toolName(), field(request, ProofField.TOOL));
        boolean tenantMatch = agreed(state.tenant(), field(request, ProofField.TENANT));
        boolean sessionMatch = agreed(state.sessionId(), field(request, ProofField.SESSION));
        boolean executionMatch = agreed(state.executionId(), field(request, ProofField.EXECUTION));
        String reason = reasonFor(state.used(), expired, userMatch, targetMatch, tenantMatch, sessionMatch, executionMatch);
        return VerifyReport.of(true, state.used(), expired, userMatch, targetMatch, tenantMatch, sessionMatch,
                executionMatch, reason);
    }

    /**
     * 按已用、过期、用户、目标、其余绑定的顺序挑一个原因。可以核销时返回空。
     *
     * @param used 是否已核销
     * @param expired 是否已过期
     * @param userMatch 用户是否匹配
     * @param targetMatch 目标是否匹配
     * @param tenantMatch 租户是否匹配
     * @param sessionMatch 会话编号是否匹配
     * @param executionMatch 执行编号是否匹配
     * @return 原因码；全部通过时为空
     */
    private static String reasonFor(boolean used, boolean expired, boolean userMatch, boolean targetMatch,
                                    boolean tenantMatch, boolean sessionMatch, boolean executionMatch) {
        if (used) {
            return PassReasons.REPLAY;
        }
        if (expired) {
            return PassReasons.EXPIRED;
        }
        if (!userMatch) {
            return PassReasons.USER_MISMATCH;
        }
        if (!targetMatch) {
            return PassReasons.TARGET_MISMATCH;
        }
        if (!tenantMatch || !sessionMatch || !executionMatch) {
            return PassReasons.BINDING_MISMATCH;
        }
        return null;
    }

    /**
     * 判断领证请求是不是缺少任一绑定字段。空白和空请求都不发证。
     *
     * @param request 领证请求
     * @return 不能发证时为真
     */
    private static boolean incomplete(IssuePassRequest request) {
        return request == null
                || blank(request.tenantId())
                || blank(request.userId())
                || blank(request.sessionId())
                || blank(request.executionId())
                || blank(request.target())
                || blank(request.toolName());
    }

    /**
     * 判断文本是不是没有可用内容。
     *
     * @param value 待判断的文本
     * @return 空或只有空白时为真
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 声称值为空时视为没有额外对照，算匹配；有内容时必须和证上的原值相同。
     *
     * @param actual 证上绑好的值
     * @param expected 调用方声称的值
     * @return 没有矛盾时为真
     */
    private static boolean agreed(String actual, String expected) {
        return blank(expected) || actual.equals(expected);
    }

    /**
     * 从可能为空的领证请求里取出一个字段，供拒绝流水使用。不读取会话令牌。
     *
     * @param request 领证请求，可以为空
     * @param field 要抄进流水的字段
     * @return 字段文本；请求为空时为空字符串
     */
    private static String text(IssuePassRequest request, IssueField field) {
        if (request == null) {
            return "";
        }
        return switch (field) {
            case TENANT -> request.tenantId();
            case USER -> request.userId();
            case EXECUTION -> request.executionId();
            case TARGET -> request.target();
            case TOOL -> request.toolName();
        };
    }

    /**
     * 从校验请求取出一个声称字段。空请求返回空字符串，比较时不会误匹配。
     *
     * @param request 校验或核销请求，可以为空
     * @param field 要读取的字段
     * @return 字段文本；缺失时为空字符串
     */
    private static String field(PassProofRequest request, ProofField field) {
        if (request == null) {
            return "";
        }
        String value = switch (field) {
            case TENANT -> request.tenantId();
            case USER -> request.userId();
            case SESSION -> request.sessionId();
            case EXECUTION -> request.executionId();
            case TARGET -> request.target();
            case TOOL -> request.toolName();
        };
        return value == null ? "" : value;
    }

    /**
     * 领证流水需要抄写的字段。故意没有会话令牌。
     */
    private enum IssueField {
        /**
         * 租户。
         */
        TENANT,
        /**
         * 登录名。
         */
        USER,
        /**
         * 执行编号。
         */
        EXECUTION,
        /**
         * 目标服务。
         */
        TARGET,
        /**
         * 工具名。
         */
        TOOL
    }

    /**
     * 校验请求里参与比较的字段。
     */
    private enum ProofField {
        /**
         * 声称的租户。
         */
        TENANT,
        /**
         * 声称的登录名。
         */
        USER,
        /**
         * 声称的会话编号。
         */
        SESSION,
        /**
         * 声称的执行编号。
         */
        EXECUTION,
        /**
         * 声称的目标服务。
         */
        TARGET,
        /**
         * 声称的工具名。
         */
        TOOL
    }

    /**
     * 一次打开证明文的结果，供校验和核销共用。
     *
     * @param report 给调用方的报告
     * @param state 内存中的证；证明无效时为空
     * @param claims 验签得到的声明；证明无效时为空
     */
    private record Inspection(VerifyReport report, PassState state, PassSigner.Claims claims) {
    }

    /**
     * 核销原子计算写回的结果。计算函数不能写流水，所以先记在这里，锁外再追加。
     */
    private static final class Holder {
        /**
         * 这次原子计算得出的结果，缺省是证明无效。
         */
        private Outcome outcome = Outcome.INVALID;
        /**
         * 和当前状态对应的校验报告。证明无效时保持为空。
         */
        private VerifyReport report;
    }

    /**
     * 核销原子计算的四种结局。
     */
    private enum Outcome {
        /**
         * 第一次核销成功。
         */
        REDEEMED,
        /**
         * 证已经用过。
         */
        REPLAY,
        /**
         * 证还在，但不能核销。
         */
        REJECTED,
        /**
         * 证明对不上内存记录。
         */
        INVALID
    }

    /**
     * 领证的两种结局，控制器据此选择 201 或 403。
     */
    public static final class IssueResult {
        private final IssuedPass pass;
        private final PassDenial denial;

        /**
         * 保存签发正文或拒绝正文，两者只会有一个非空。
         *
         * @param pass 签发成功时的正文
         * @param denial 拒绝时的正文
         */
        private IssueResult(IssuedPass pass, PassDenial denial) {
            this.pass = pass;
            this.denial = denial;
        }

        /**
         * 包装一张已经签发的证。
         *
         * @param pass 签发正文
         * @return 成功结果
         */
        public static IssueResult issued(IssuedPass pass) {
            return new IssueResult(pass, null);
        }

        /**
         * 包装一次不发证的决定。
         *
         * @param reason 原因码
         * @return 拒绝结果
         */
        public static IssueResult denied(String reason) {
            return new IssueResult(null, PassDenial.of(reason));
        }

        /**
         * 是否发出了证。
         *
         * @return 有证明文时为真
         */
        public boolean issued() {
            return pass != null;
        }

        /**
         * 返回签发正文。拒绝时为空。
         *
         * @return 签发正文或空
         */
        public IssuedPass pass() {
            return pass;
        }

        /**
         * 返回拒绝正文。成功时为空。
         *
         * @return 拒绝正文或空
         */
        public PassDenial denial() {
            return denial;
        }
    }

    /**
     * 核销的 HTTP 语义：成功、重放或拒绝。
     */
    public enum RedeemStatus {
        /**
         * 第一次核销，响应 200。
         */
        REDEEMED,
        /**
         * 重复核销，响应 409。
         */
        REPLAY,
        /**
         * 不能核销，响应 403。
         */
        REJECTED
    }

    /**
     * 核销结果，包含状态码类别和响应正文。
     */
    public static final class RedeemResult {
        private final RedeemStatus status;
        private final RedeemResponse body;

        /**
         * 保存核销类别和响应正文。
         *
         * @param status 成功、重放或拒绝
         * @param body 给调用方的正文
         */
        private RedeemResult(RedeemStatus status, RedeemResponse body) {
            this.status = status;
            this.body = body;
        }

        /**
         * 包装一次成功的首次核销。
         *
         * @param body 响应正文
         * @return 成功结果
         */
        public static RedeemResult redeemed(RedeemResponse body) {
            return new RedeemResult(RedeemStatus.REDEEMED, body);
        }

        /**
         * 包装一次重放。
         *
         * @param body 响应正文
         * @return 重放结果
         */
        public static RedeemResult replay(RedeemResponse body) {
            return new RedeemResult(RedeemStatus.REPLAY, body);
        }

        /**
         * 包装一次拒绝核销。
         *
         * @param body 响应正文
         * @return 拒绝结果
         */
        public static RedeemResult rejected(RedeemResponse body) {
            return new RedeemResult(RedeemStatus.REJECTED, body);
        }

        /**
         * 返回核销类别。
         *
         * @return 成功、重放或拒绝
         */
        public RedeemStatus status() {
            return status;
        }

        /**
         * 返回响应正文。
         *
         * @return 核销响应
         */
        public RedeemResponse body() {
            return body;
        }
    }
}
