package dev.jasagent.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对执行日志落在执行目录、事件种类齐全，以及凭据不会被写进日志。
 */
class ExecutionTraceTest {
    @TempDir
    Path temp;

    /**
     * 记下一件受理事件后，trace.log 和 events.jsonl 都出现在该执行自己的日志目录。断言失败则本测试失败。
     */
    @Test
    void traceFilesUnderExecutionDirectory() throws Exception {
        Path workspace = temp.resolve("workspace");
        String executionId = UUID.randomUUID().toString();
        FileExecutionTrace trace = new FileExecutionTrace();
        join(trace.event(workspace, executionId, TraceEventKind.ACCEPTED, "accepted"));
        Path directory = workspace.resolve("jasagent").resolve("logs").resolve(executionId);
        assertEquals(directory, FileExecutionTrace.directory(workspace, executionId));
        assertTrue(Files.isRegularFile(directory.resolve("trace.log")));
        assertTrue(Files.isRegularFile(directory.resolve("events.jsonl")));
    }

    /**
     * 每种事件种类都要同时出现在可读日志和 JSON 行里，且行数与种类数一致。断言失败则本测试失败。
     */
    @Test
    void eventsCoverRequiredKinds() throws Exception {
        Path workspace = temp.resolve("workspace");
        String executionId = UUID.randomUUID().toString();
        FileExecutionTrace trace = new FileExecutionTrace();
        for (TraceEventKind kind : TraceEventKind.values()) {
            join(trace.event(workspace, executionId, kind, "body-" + kind.name()));
        }
        Path directory = FileExecutionTrace.directory(workspace, executionId);
        String log = Files.readString(directory.resolve("trace.log"));
        String jsonl = Files.readString(directory.resolve("events.jsonl"));
        assertEquals(TraceEventKind.values().length, jsonl.lines().filter(line -> !line.isBlank()).count());
        for (TraceEventKind kind : TraceEventKind.values()) {
            assertTrue(log.contains(kind.name()), kind.name());
            assertTrue(jsonl.contains(kind.name()), kind.name());
        }
    }

    /**
     * 事件说明里的 Bearer 凭据必须从可读日志和 JSON 行中去掉。断言失败则本测试失败。
     */
    @Test
    void authorizationValueIsRemoved() throws Exception {
        Path workspace = temp.resolve("workspace");
        String executionId = UUID.randomUUID().toString();
        FileExecutionTrace trace = new FileExecutionTrace();
        join(trace.event(workspace, executionId, TraceEventKind.MODEL_ROUTE, "Authorization: Bearer super-secret-token"));
        Path directory = FileExecutionTrace.directory(workspace, executionId);
        String all = Files.readString(directory.resolve("trace.log")) + Files.readString(directory.resolve("events.jsonl"));
        assertFalse(all.contains("super-secret-token"));
        assertFalse(all.contains("Bearer"));
    }

    /**
     * 堆栈要写入 trace.log，并保留异常说明和调用位置。断言失败则本测试失败。
     */
    @Test
    void stackGoesToTraceLog() throws Exception {
        Path workspace = temp.resolve("workspace");
        String executionId = UUID.randomUUID().toString();
        FileExecutionTrace trace = new FileExecutionTrace();
        join(trace.stack(workspace, executionId, new IllegalStateException("stack-marker")));
        String log = Files.readString(FileExecutionTrace.directory(workspace, executionId).resolve("trace.log"));
        assertTrue(log.contains("stack-marker"));
        assertTrue(log.contains("at "));
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
