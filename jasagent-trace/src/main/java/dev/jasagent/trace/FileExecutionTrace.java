package dev.jasagent.trace;

import dev.jasagent.api.concurrent.VirtualIo;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

public final class FileExecutionTrace {
    public static Path directory(Path workspace, String executionId) {
        return workspace.resolve("jasagent").resolve("logs").resolve(executionId);
    }

    public CompletionStage<Void> event(Path workspace, String executionId, TraceEventKind kind, String message) {
        return VirtualIo.supply(() -> {
            String safe = redact(message);
            append(workspace, executionId, "trace.log", Instant.now() + " " + kind.name() + " " + safe + System.lineSeparator());
            append(workspace, executionId, "events.jsonl", json(executionId, kind.name(), safe) + System.lineSeparator());
            return null;
        });
    }

    public CompletionStage<Void> stack(Path workspace, String executionId, Throwable error) {
        return VirtualIo.supply(() -> {
            StringWriter writer = new StringWriter();
            error.printStackTrace(new PrintWriter(writer));
            append(workspace, executionId, "trace.log", writer + System.lineSeparator());
            return null;
        });
    }

    static String redact(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("(?i)authorization\\s*[:=]\\s*bearer\\s+\\S+", "")
                .replaceAll("(?i)authorization\\s*[:=]\\s*\\S+", "")
                .replaceAll("(?i)bearer\\s+\\S+", "");
    }

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

    private static String json(String executionId, String kind, String message) {
        return "{\"executionId\":\"" + escape(executionId) + "\",\"kind\":\"" + escape(kind) + "\",\"message\":\"" + escape(message) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
