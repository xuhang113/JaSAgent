package dev.jasagent.exception;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对异步边界不会吞掉失败、任务消费失败后仍能继续，以及返回正文不带堆栈。
 */
class AsyncExceptionTest {
    /**
     * 确认错误码枚举包含模型、工具、插件、检索、工作流、持久化、认证、中断、取消和内部错误。断言失败则本测试失败。
     */
    @Test
    void errorCodesPresent() {
        Set<ErrorCode> required = EnumSet.of(
                ErrorCode.LLM,
                ErrorCode.TOOL,
                ErrorCode.PLUGIN,
                ErrorCode.MCP,
                ErrorCode.RAG,
                ErrorCode.WORKFLOW,
                ErrorCode.WORKFLOW_MAX_STEPS,
                ErrorCode.PERSIST,
                ErrorCode.AUTH,
                ErrorCode.INTERRUPTED,
                ErrorCode.CANCELLED,
                ErrorCode.INTERNAL);
        assertTrue(EnumSet.allOf(ErrorCode.class).containsAll(required));
    }

    /**
     * 动作在内部吃掉异常后返回空时，守卫仍要报内部错误，并带上本次执行标识。断言失败则本测试失败。
     */
    @Test
    void swallowedNullIsRejected() {
        String executionId = UUID.randomUUID().toString();
        Callable<String> swallowed = () -> {
            try {
                throw new IllegalStateException("hidden");
            } catch (RuntimeException ex) {
                return null;
            }
        };
        AgentException error = assertThrows(AgentException.class, () -> join(AsyncBoundary.guard(executionId, swallowed)));
        assertEquals(ErrorCode.INTERNAL, error.code());
        assertEquals(executionId, error.executionId());
    }

    /**
     * 动作直接抛出的异常不能变成空结果，守卫要保留原因并写上执行标识。断言失败则本测试失败。
     */
    @Test
    void thrownExceptionIsNotReturnedAsNull() {
        String executionId = UUID.randomUUID().toString();
        AgentException error = assertThrows(AgentException.class, () -> join(AsyncBoundary.guard(executionId, () -> {
            throw new IllegalStateException("boom");
        })));
        assertEquals(executionId, error.executionId());
        assertNotNull(error.getCause());
    }

    /**
     * 第一个任务抛出工具错误时要记入失败槽并得到失败结局，随后另一个任务仍能正常完成。断言失败则本测试失败。
     */
    @Test
    void consumerMarksTaskFailedAndContinues() {
        String executionId = UUID.randomUUID().toString();
        List<String> failed = new ArrayList<>();
        TaskFailureSink sink = (id, taskId, error) -> {
            assertEquals(executionId, id);
            assertEquals(ErrorCode.TOOL, error.code());
            assertEquals(executionId, error.executionId());
            failed.add(taskId);
        };
        TaskConsumer consumer = new TaskConsumer();
        ConsumeOutcome first = join(consumer.consume(executionId, "task-1", () -> {
            throw new AgentException(ErrorCode.TOOL, "nope", executionId, null);
        }, sink));
        assertEquals(ConsumeOutcome.FAILED, first);
        assertEquals(List.of("task-1"), failed);
        ConsumeOutcome second = join(consumer.consume(executionId, "task-2", () -> "ok", sink));
        assertEquals(ConsumeOutcome.COMPLETED, second);
        assertEquals(List.of("task-1"), failed);
    }

    /**
     * 返回正文只含执行标识和工具错误码，堆栈文本只出现在追踪出口里。断言失败则本测试失败。
     */
    @Test
    void httpBodyHasExecutionIdAndCodeWithoutStack() {
        String executionId = UUID.randomUUID().toString();
        List<String> stacks = new ArrayList<>();
        AgentException error = new AgentException(ErrorCode.TOOL, "stack-marker", executionId, new IllegalStateException("stack-marker"));
        HttpErrorBody body = HttpErrors.body(error, stacks::add);
        assertEquals(executionId, body.executionId());
        assertEquals("TOOL", body.code());
        assertTrue(!body.toString().contains("stack-marker"));
        assertTrue(!body.toString().contains("at "));
        assertEquals(1, stacks.size());
        assertTrue(stacks.get(0).contains("stack-marker"));
        assertTrue(stacks.get(0).contains("at "));
    }

    /**
     * 守卫里的动作必须跑在虚拟线程上。断言失败则本测试失败。
     */
    @Test
    void guardRunsOnVirtualThread() {
        String executionId = UUID.randomUUID().toString();
        AtomicBoolean virtual = new AtomicBoolean(false);
        join(AsyncBoundary.guard(executionId, () -> {
            virtual.set(Thread.currentThread().isVirtual());
            return "ok";
        }));
        assertTrue(virtual.get());
    }

    /**
     * 在限定时间内取回异步结果，并把包装异常还原成原来的运行时异常；等待被中断时重新置上中断标记。
     *
     * @param stage 待等待的异步阶段
     * @return 阶段成功时的结果
     */
    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
