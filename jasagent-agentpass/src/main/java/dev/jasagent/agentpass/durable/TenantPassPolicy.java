package dev.jasagent.agentpass.durable;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.tool.ReadOnlyTools;

/**
 * 按租户策略裁决一次工具或插件调用。规则只来自已有 MySQL，不读 policies.yaml。
 * 未命中且不是只读工具时留下待批，供同一用户以后批准或拒绝。
 */
public final class TenantPassPolicy {
    /**
     * 策略对象是工具名。
     */
    public static final String KIND_TOOL = "tool";
    /**
     * 策略对象是插件标识。
     */
    public static final String KIND_PLUGIN = "plugin";
    /**
     * 命中了租户策略。
     */
    public static final String REASON_POLICY = "policy";
    /**
     * 没有策略，但是只读工具，直接允许。
     */
    public static final String REASON_READ_ONLY = "read-only";
    /**
     * 同一用户已经批准过这次调用。
     */
    public static final String REASON_APPROVED = "approved";
    /**
     * 同一用户已经拒绝过这次调用。
     */
    public static final String REASON_DENIED = "denied";
    /**
     * 还没有策略，也还没有人决定，需要留下待批。
     */
    public static final String REASON_PENDING = "approval-required";

    private final AgentPassLedger ledger;

    /**
     * 使用已经打开的账本。账本负责建表和租户隔离。
     *
     * @param ledger 通行证账本，不能为空
     */
    public TenantPassPolicy(AgentPassLedger ledger) {
        if (ledger == null) {
            throw new AgentException(ErrorCode.INTERNAL, "pass ledger");
        }
        this.ledger = ledger;
    }

    /**
     * 裁决这次调用。允许和拒绝立即返回；未命中则取回或写下待批。
     * 副作用：未命中且还没有行时向待批表插入一行。
     *
     * @param tenantId 已经核对过的租户
     * @param userId 登录名
     * @param executionId 执行编号
     * @param toolName 工具名，没有时为空
     * @param pluginId 插件标识，没有时为空
     * @param target 领证目标，进程内申请可以是空
     * @param sessionId 登录会话编号，可以是空
     * @return 允许、拒绝或等待，等待时通行证带有编号
     */
    public Result judge(String tenantId, String userId, String executionId, String toolName, String pluginId,
                        String target, String sessionId) {
        String tool = text(toolName);
        String plugin = text(pluginId);
        if (tool.isEmpty() && plugin.isEmpty()) {
            throw new AgentException(ErrorCode.INTERNAL, "pass target missing");
        }
        String kind = tool.isEmpty() ? KIND_PLUGIN : KIND_TOOL;
        String subject = tool.isEmpty() ? plugin : tool;
        String policy = ledger.policy(tenantId, kind, subject);
        if ("DENY".equals(policy)) {
            return Result.deny(new Pass(PassDecision.DENY, REASON_POLICY));
        }
        if ("ALLOW".equals(policy)) {
            return Result.allow(new Pass(PassDecision.ALLOW, REASON_POLICY));
        }
        if (!tool.isEmpty() && ReadOnlyTools.is(tool)) {
            return Result.allow(new Pass(PassDecision.ALLOW, REASON_READ_ONLY));
        }
        String prior = ledger.approval(tenantId, userId, executionId, tool, plugin);
        if ("ALLOW".equals(prior)) {
            return Result.allow(new Pass(PassDecision.ALLOW, REASON_APPROVED));
        }
        if ("DENY".equals(prior)) {
            return Result.deny(new Pass(PassDecision.DENY, REASON_DENIED));
        }
        Pass pending = ledger.ensurePending(tenantId, userId, text(sessionId), executionId, tool, plugin, text(target));
        return Result.pending(pending);
    }

    /**
     * 把空引用收成空串。
     *
     * @param value 原始文本
     * @return 非空文本
     */
    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 一次裁决。等待时 {@link #pass()} 带有可以审批的编号。
     *
     * @param kind 允许、拒绝或等待
     * @param pass 给调用方的通行证
     */
    public record Result(PassDecision kind, Pass pass) {
        /**
         * 允许执行。
         *
         * @param pass 允许通行证
         * @return 允许结果
         */
        public static Result allow(Pass pass) {
            return new Result(PassDecision.ALLOW, pass);
        }

        /**
         * 拒绝执行。
         *
         * @param pass 拒绝通行证
         * @return 拒绝结果
         */
        public static Result deny(Pass pass) {
            return new Result(PassDecision.DENY, pass);
        }

        /**
         * 留下待批。
         *
         * @param pass 带编号的等待通行证
         * @return 等待结果
         */
        public static Result pending(Pass pass) {
            return new Result(PassDecision.PENDING, pass);
        }
    }
}
