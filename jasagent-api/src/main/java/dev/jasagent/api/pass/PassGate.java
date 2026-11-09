package dev.jasagent.api.pass;

import dev.jasagent.api.execution.ExecutionStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 工具和插件调用的通行证门。先按用户策略决定放行、拒绝、跳过或等待，等待中的调用可以由用户事后决定。
 */
public interface PassGate {
    /**
     * 为一次工具或插件调用核发通行证。命中策略时按策略决定；只读工具可直接允许；需要人批时返回等待并可能把执行标成挂起。
     *
     * @param request 用户、执行、目录、工具、插件和参数摘要
     * @return 完成后给出通行证的阶段，决定为允许、拒绝、跳过或等待
     */
    CompletionStage<Pass> acquire(PassRequest request);

    /**
     * 把用户的允许或拒绝对到一张等待中的通行证上，并写回原记录。默认实现不处理决定。
     *
     * @param approval 用户、执行、通行证编号、决定和用户目录
     * @return 支持该入口的实现返回更新后的通行证；默认实现返回已失败的阶段，原因是不支持 decide
     */
    default CompletionStage<Pass> decide(PassApproval approval) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("decide"));
    }

    /**
     * 列出某个用户目录里仍在等待的通行证。默认实现没有待办。
     *
     * @param userId 用户登录名
     * @param userDirectory 该用户的 jasagent 目录，待办文件位于其中的 auth/pending
     * @return 完成后给出等待中通行证的阶段；默认实现的结果是空列表
     */
    default CompletionStage<List<Pass>> pending(String userId, Path userDirectory) {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * 查询通行证门为某次执行记下的状态。出现等待中的通行证时，实现会把执行记为挂起。
     *
     * @param executionId 执行编号
     * @return 完成后给出执行状态的阶段；这扇门不跟踪状态时结果为 null，默认实现即是如此
     */
    default CompletionStage<ExecutionStatus> executionStatus(String executionId) {
        return CompletableFuture.completedFuture(null);
    }
}
