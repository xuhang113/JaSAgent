package dev.jasagent.api.concurrent;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把阻塞调用放到虚拟线程上，并统一成异步阶段。
 * 文件、数据库和模型调用把阻塞部分提交到这里的共享执行器。
 */
public final class VirtualIo {
    /**
     * 每个任务一条虚拟线程的共享执行器。
     * 进程内的阻塞边界都提交到它上面，不按调用方各建一份。
     */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 禁止从外面构造。
     * 执行器是共享的，调用方使用静态方法。
     */
    private VirtualIo() {
    }

    /**
     * 返回进程内共享的虚拟线程执行器。
     * 调用方提交到这里的任务与 {@link #supply} 使用同一组线程。
     *
     * @return 共享执行器
     */
    public static ExecutorService executor() {
        return EXECUTOR;
    }

    /**
     * 在共享虚拟线程上执行调用，并把结果包成阶段。
     * 调用抛出的 {@link dev.jasagent.api.exception.AgentException} 原样传出；其他异常改写成内部错误，执行标识为空，原因保留原异常。
     *
     * @param <T> 调用的结果类型
     * @param call 要放到虚拟线程上的调用
     * @return 调用完成后得到结果的阶段；失败时阶段以异常结束
     */
    public static <T> CompletionStage<T> supply(Callable<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.call();
            } catch (AgentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new AgentException(ErrorCode.INTERNAL, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), null, ex);
            }
        }, EXECUTOR);
    }
}
