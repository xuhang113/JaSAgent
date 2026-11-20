package dev.jasagent.agentpass.server;

import dev.jasagent.agentpass.durable.AgentPassLedger;
import dev.jasagent.agentpass.durable.TenantPassPolicy;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.PassDecision;

import java.util.regex.Pattern;

/**
 * 发证前核对登录会话、租户和策略。会话和租户只认 020 的 MySQL，不读家目录里的会话文件或 policies.yaml。
 * 未命中策略时留下待批并拒绝发证。只认允许可以发一次性证。
 */
public final class HumanAccess {
    /**
     * 会话编号必须能放进 020 登录会话表的主键，并且不能带着路径。
     */
    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private final AgentPassLedger ledger;
    private final TenantPassPolicy policy;

    /**
     * 绑定已经打开的通行证账本。策略和待批都写在这个库里。
     *
     * @param ledger 含 020 账号、登录会话、策略和待批的账本，不能为空
     */
    public HumanAccess(AgentPassLedger ledger) {
        if (ledger == null) {
            throw new IllegalStateException("agentpass store");
        }
        this.ledger = ledger;
        this.policy = new TenantPassPolicy(ledger);
    }

    /**
     * 判断这次领证请求能不能发证。会话编号用来查登录会话，登录令牌不会出现在返回的决定里。
     * 副作用：策略未命中时向待批表插入或取回一行。
     *
     * @param request 含租户、用户、会话编号、执行、目标和工具名的领证请求
     * @return 允许时带上会话编号；否则带上不发证的原因码
     */
    public Decision decide(IssuePassRequest request) {
        String userId = request.userId().trim();
        String sessionId = request.sessionId().trim();
        String tenantId = request.tenantId().trim();
        if (!SESSION_ID.matcher(sessionId).matches()) {
            return Decision.deny(PassReasons.NOT_LOGGED_IN);
        }
        AgentPassLedger.Identity identity;
        try {
            identity = ledger.identity(sessionId);
        } catch (RuntimeException ex) {
            return Decision.deny(PassReasons.ACCESS_CHECK_FAILED);
        }
        if (identity == null) {
            return Decision.deny(PassReasons.NOT_LOGGED_IN);
        }
        if (!userId.equals(identity.login()) || !tenantId.equals(identity.tenantId())) {
            return Decision.deny(PassReasons.SESSION_MISMATCH);
        }
        TenantPassPolicy.Result result;
        try {
            result = policy.judge(identity.tenantId(), userId, request.executionId().trim(), request.toolName().trim(),
                    "", request.target().trim(), sessionId);
        } catch (AgentException ex) {
            if (ex.code() == ErrorCode.AUTH) {
                return Decision.deny(PassReasons.NOT_LOGGED_IN);
            }
            return Decision.deny(ex.code() == ErrorCode.INTERNAL ? PassReasons.POLICY_UNREADABLE : PassReasons.ACCESS_CHECK_FAILED);
        } catch (RuntimeException ex) {
            return Decision.deny(PassReasons.ACCESS_CHECK_FAILED);
        }
        if (result == null || result.kind() == null) {
            return Decision.deny(PassReasons.ACCESS_CHECK_FAILED);
        }
        return switch (result.kind()) {
            case ALLOW -> Decision.allow(sessionId);
            case DENY -> Decision.deny(PassReasons.POLICY_DENY);
            case PENDING -> Decision.deny(PassReasons.APPROVAL_REQUIRED);
            case SKIPPED -> Decision.deny(PassReasons.AGENTPASS_NOT_ENABLED);
        };
    }

    /**
     * 人证核对的结果。允许时只带回会话编号，不带回登录令牌。
     *
     * @param allowed 可以签发一次性证时为真
     * @param sessionId 已登录会话的编号；拒绝时为空
     * @param reason 拒绝原因；允许时为空
     */
    public record Decision(boolean allowed, String sessionId, String reason) {
        /**
         * 构造一个允许发证的决定。
         *
         * @param sessionId 核对通过的会话编号
         * @return 允许决定
         */
        public static Decision allow(String sessionId) {
            return new Decision(true, sessionId, null);
        }

        /**
         * 构造一个不发证的决定。
         *
         * @param reason {@link PassReasons} 中的原因码
         * @return 拒绝决定
         */
        public static Decision deny(String reason) {
            return new Decision(false, null, reason);
        }
    }
}
