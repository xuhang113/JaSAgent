package dev.jasagent.exception;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 把可能抛出的调用包进虚拟线程，并保证失败都变成带执行标识的业务异常。
 */
public final class AsyncBoundary {
    /**
     * 禁止实例化，异步边界只通过静态守卫使用。
     */
    private AsyncBoundary() {
    }

    /**
     * 在虚拟线程上执行调用。返回空、或抛出未带执行标识的异常时，改成带该执行标识的业务异常。
     *
     * @param executionId 这次异步调用所属的执行
     * @param call 要在虚拟线程里运行的动作，不允许返回空
     * @return 成功时带上动作结果；失败时以业务异常结束的阶段
     */
    public static <T> CompletionStage<T> guard(String executionId, Callable<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                T value = call.call();
                if (value == null) {
                    throw new AgentException(ErrorCode.INTERNAL, "异步边界不能返回空", executionId, null);
                }
                return value;
            } catch (AgentException ex) {
                if (ex.executionId() == null || ex.executionId().isBlank()) {
                    throw new AgentException(ex.code(), ex.getMessage(), executionId, ex.getCause() == null ? ex : ex.getCause());
                }
                throw ex;
            } catch (Exception ex) {
                throw new AgentException(
                        ErrorCode.INTERNAL,
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                        executionId,
                        ex);
            }
        }, VirtualIo.executor());
    }
}
