package dev.jasagent.trace;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.text.SecretRedactor;
import dev.jasagent.api.trace.TraceEvent;
import dev.jasagent.api.trace.TraceWriter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.CompletionStage;

public final class FileTraceWriter implements TraceWriter {
    @Override
    public CompletionStage<Void> event(Path workspace, String executionId, TraceEvent event) {
        return VirtualIo.supply(() -> {
            Path dir = workspace.resolve("jasagent").resolve("logs").resolve(executionId);
            Files.createDirectories(dir);
            String message = SecretRedactor.redact(event.message());
            String logLine = event.type() + " " + message;
            Files.writeString(dir.resolve("trace.log"), logLine + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(dir.resolve("events.jsonl"), toJson(event.type(), message, event.fields()) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return null;
        });
    }

    @Override
    public CompletionStage<String> readLog(Path workspace, String executionId) {
        return read(workspace, executionId, "trace.log");
    }

    @Override
    public CompletionStage<String> readEvents(Path workspace, String executionId) {
        return read(workspace, executionId, "events.jsonl");
    }

    private CompletionStage<String> read(Path workspace, String executionId, String fileName) {
        return VirtualIo.supply(() -> {
            Path file = workspace.resolve("jasagent").resolve("logs").resolve(executionId).resolve(fileName);
            if (!Files.isRegularFile(file)) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        });
    }

    private static String toJson(String type, String message, Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"type\":").append(quote(type));
        builder.append(",\"message\":").append(quote(message));
        builder.append(",\"fields\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(quote(entry.getKey())).append(':').append(quote(SecretRedactor.redact(entry.getValue())));
        }
        builder.append("}}");
        return builder.toString();
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }
}
