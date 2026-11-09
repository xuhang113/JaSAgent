package dev.jasagent.llm;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * 等待模型相关的异步阶段，并把受检失败收成统一的模型错误。
 */
final class Stages {
    /**
     * 禁止实例化。等待只通过静态方法完成。
     */
    private Stages() {
    }

    /**
     * 阻塞到阶段结束。运行时异常原样抛出，其他失败收成已脱敏的模型错误。
     *
     * @param <T> 阶段结果的类型
     * @param stage 要等待的异步阶段
     * @return 阶段成功时的结果
     */
    static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AgentException(ErrorCode.LLM, AuthorizationRedactor.redact(cause.getMessage()), null, cause);
        }
    }
}
