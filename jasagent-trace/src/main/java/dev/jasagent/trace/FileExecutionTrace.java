package dev.jasagent.trace;

import dev.jasagent.api.concurrent.VirtualIo;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/**
 * 把一次执行的事件和堆栈追加到该执行自己的日志目录。
 */
public final class FileExecutionTrace {
    /**
     * 定位某次执行的日志目录。
     *
     * @param workspace 工作区根目录
     * @param executionId 执行标识，也是日志子目录名
     * @return {@code workspace/jasagent/logs/执行标识}
     */
    public static Path directory(Path workspace, String executionId) {
        return workspace.resolve("jasagent").resolve("logs").resolve(executionId);
    }

    /**
     * 抹掉消息里的凭据后，同时追加可读日志和一行 JSON 事件。
     *
     * @param workspace 工作区根目录
     * @param executionId 这次执行的标识
     * @param kind 事件种类
     * @param message 事件说明，其中的授权凭据会被去掉
     * @return 两份日志都追加完成后的阶段
     */
    public CompletionStage<Void> event(Path workspace, String executionId, TraceEventKind kind, String message) {
        return VirtualIo.supply(() -> {
            String safe = redact(message);
            append(workspace, executionId, "trace.log", Instant.now() + " " + kind.name() + " " + safe + System.lineSeparator());
            append(workspace, executionId, "events.jsonl", json(executionId, kind.name(), safe) + System.lineSeparator());
            return null;
        });
    }

    /**
     * 把异常的完整堆栈追加到可读日志，不写入 JSON 事件流。
     *
     * @param workspace 工作区根目录
     * @param executionId 这次执行的标识
     * @param error 需要留下堆栈的异常
     * @return 堆栈写入 trace.log 后完成的阶段
     */
    public CompletionStage<Void> stack(Path workspace, String executionId, Throwable error) {
        return VirtualIo.supply(() -> {
            StringWriter writer = new StringWriter();
            error.printStackTrace(new PrintWriter(writer));
            append(workspace, executionId, "trace.log", writer + System.lineSeparator());
            return null;
        });
    }

    /**
     * 去掉文本中的 Authorization 和 Bearer 凭据，避免密钥进日志。
     *
     * @param text 原始事件说明；空则当成空串
     * @return 去掉凭据后的文本
     */
    static String redact(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("(?i)authorization\\s*[:=]\\s*bearer\\s+\\S+", "")
                .replaceAll("(?i)authorization\\s*[:=]\\s*\\S+", "")
                .replaceAll("(?i)bearer\\s+\\S+", "");
    }

    /**
     * 在执行日志目录里按文件名追加一段文本，并用绝对路径字符串串行化同一文件的写入。副作用是创建目录并追加文件；写失败时抛出非法状态异常。
     *
     * @param workspace 工作区根目录
     * @param executionId 执行标识
     * @param fileName trace.log 或 events.jsonl
     * @param text 要追加的一段文本
     */
    private static void append(Path workspace, String executionId, String fileName, String text) {
        try {
            Path directory = directory(workspace, executionId);
            Files.createDirectories(directory);
            Path file = directory.resolve(fileName);
            synchronized (file.toAbsolutePath().toString().intern()) {
                Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("无法写入执行日志", ex);
        }
    }

    /**
     * 把执行标识、事件种类和说明收成一行 JSON。
     *
     * @param executionId 执行标识
     * @param kind 事件种类名称
     * @param message 已经去掉凭据的说明
     * @return 单行 JSON 对象
     */
    private static String json(String executionId, String kind, String message) {
        return "{\"executionId\":\"" + escape(executionId) + "\",\"kind\":\"" + escape(kind) + "\",\"message\":\"" + escape(message) + "\"}";
    }

    /**
     * 转义 JSON 字符串里的反斜杠、引号和换行。
     *
     * @param value 原始文本
     * @return 可放进 JSON 字符串的文本
     */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
