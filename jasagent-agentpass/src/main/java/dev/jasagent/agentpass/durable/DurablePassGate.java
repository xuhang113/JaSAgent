package dev.jasagent.agentpass.durable;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassApproval;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.agentpass.durable.AgentPassLedger.DecisionOutcome;

import java.util.concurrent.CompletionStage;

/**
 * 按 020 租户读取 MySQL 里的策略。未命中时把待批写进同一库，不读用户目录里的策略文件。
 * 批准或拒绝只接受待批上的那个用户。
 */
public final class DurablePassGate implements PassGate {
    private final AgentPassLedger ledger;
    private final TenantPassPolicy policy;

    /**
     * 打开给定账本上的策略门。
     *
     * @param ledger 已建表的通行证账本，不能为空
     */
    public DurablePassGate(AgentPassLedger ledger) {
        this.ledger = ledger;
        this.policy = new TenantPassPolicy(ledger);
    }

    /**
     * 按登录名在账号表里的租户裁决。没有账号时拒绝，不再去读 policies.yaml。
     * 副作用：未命中时可能插入待批。
     *
     * @param request 含用户、执行、工具或插件的申请
     * @return 允许、拒绝或等待
     */
    @Override
    public CompletionStage<Pass> acquire(PassRequest request) {
        return VirtualIo.supply(() -> acquireSync(request));
    }

    /**
     * 把等待中的通行证改成允许或拒绝。用户或执行对不上时失败，已经决定过的不能再改。
     * 副作用：更新待批行。
     *
     * @param approval 审批人、执行、通行证编号和决定
     * @return 更新后的通行证；没有这张待批时仍返回原决定，方便调用方只改内存里的非待批记录
     */
    @Override
    public CompletionStage<Pass> decide(PassApproval approval) {
        return VirtualIo.supply(() -> {
            DecisionOutcome outcome = decideStored(approval);
            if (outcome == DecisionOutcome.FORBIDDEN) {
                throw new AgentException(ErrorCode.AUTH, "pass");
            }
            if (outcome == DecisionOutcome.ALREADY) {
                throw new AgentException(ErrorCode.INTERNAL, "pass already decided");
            }
            if (outcome == DecisionOutcome.MISSING) {
                throw new AgentException(ErrorCode.AUTH, "pass");
            }
            return ledger.findPending(approval.passId());
        });
    }

    /**
     * 同步审批。没有待批时返回 {@link DecisionOutcome#MISSING}，不抛异常，方便执行记录里那些没有落库的通行证继续走原来的内存更新。
     * 副作用：成功时更新待批行。
     *
     * @param approval 审批请求
     * @return 更新、缺失、越权或已经决定
     */
    public DecisionOutcome decideStored(PassApproval approval) {
        if (approval.decision() != PassDecision.ALLOW && approval.decision() != PassDecision.DENY) {
            throw new AgentException(ErrorCode.INTERNAL, "decision");
        }
        return ledger.decide(approval.userId(), approval.executionId(), approval.passId(), approval.decision());
    }

    /**
     * 同步申请。租户以账号表为准。
     *
     * @param request 本次申请
     * @return 裁决后的通行证
     */
    private Pass acquireSync(PassRequest request) {
        if (request.userId() == null || request.userId().isBlank()
                || request.executionId() == null || request.executionId().isBlank()) {
            throw new AgentException(ErrorCode.INTERNAL, "pass request");
        }
        String tenant = ledger.tenantOf(request.userId());
        if (tenant == null || tenant.isBlank()) {
            throw new AgentException(ErrorCode.AUTH, "pass");
        }
        return policy.judge(tenant, request.userId(), request.executionId(), request.toolName(), request.pluginId(), "", "")
                .pass();
    }
}
