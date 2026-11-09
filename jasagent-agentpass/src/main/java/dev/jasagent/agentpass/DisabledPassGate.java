package dev.jasagent.agentpass;

import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 通行证关闭时使用的门，任何申请都直接跳过，不读策略、也不挂起执行。
 */
final class DisabledPassGate implements PassGate {
    /**
     * 立即返回跳过决定，原因是通行证未启用。
     *
     * @param request 本次申请；关闭时不查看其中的工具或插件
     * @return 已经完成的跳过结果
     */
    @Override
    public CompletionStage<Pass> acquire(PassRequest request) {
        return CompletableFuture.completedFuture(new Pass(PassDecision.SKIPPED, "agentpass-not-enabled"));
    }
}
