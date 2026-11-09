package dev.jasagent.rag;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * 把多条召回任务交给同一个执行器，并按提交顺序收回结果。
 */
public final class PathFanout {
    private final Executor executor;

    /**
     * 记住跑召回任务的执行器。
     *
     * @param executor 执行每条召回的执行器，检索路径上通常是虚拟线程
     */
    public PathFanout(Executor executor) {
        this.executor = executor;
    }

    /**
     * 同时提交全部任务，等它们都结束后按原顺序给出结果。
     * 任务抛出的受检异常会包成内部错误；运行时异常保持原样。任一任务失败时整个阶段失败。
     *
     * @param <T> 单个任务的结果类型
     * @param tasks 要并行执行的任务，顺序与返回列表一致
     * @return 全部成功后的结果；顺序和任务顺序相同
     */
    public <T> CompletionStage<List<T>> all(List<Callable<T>> tasks) {
        List<CompletableFuture<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (RuntimeException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new AgentException(ErrorCode.INTERNAL, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), null, ex);
                }
            }, executor));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<T> values = new ArrayList<>();
            for (CompletableFuture<T> future : futures) {
                values.add(future.join());
            }
            return values;
        });
    }
}
