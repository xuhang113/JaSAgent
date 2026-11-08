package dev.jasagent.core;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.context.CorrectionNote;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class CorrectionStore {
    public CompletionStage<Void> append(Path userDirectory, CorrectionNote note, Consumer<CorrectionNote> snapshot) {
        return VirtualIo.supply(() -> {
            if (userDirectory == null) {
                throw new AgentException(ErrorCode.INTERNAL, "userDirectory");
            }
            if (snapshot == null) {
                throw new AgentException(ErrorCode.INTERNAL, "snapshot");
            }
            if (note == null) {
                throw new AgentException(ErrorCode.INTERNAL, "note");
            }
            requireSegment(note.noteId(), "noteId");
            requireSegment(note.sessionId(), "sessionId");
            if (note.executionId() == null || note.executionId().isBlank()) {
                throw new AgentException(ErrorCode.INTERNAL, "executionId");
            }
            if (note.createdAt() == null) {
                throw new AgentException(ErrorCode.INTERNAL, "createdAt");
            }
            if (note.source() == null) {
                throw new AgentException(ErrorCode.INTERNAL, "source");
            }
            if (note.text() == null || note.text().isBlank()) {
                throw new AgentException(ErrorCode.INTERNAL, "text");
            }
            Path corrections = userDirectory.toAbsolutePath().normalize().resolve("corrections").normalize();
            Path file = corrections.resolve(note.sessionId()).resolve(note.noteId() + ".json").normalize();
            if (!file.startsWith(corrections)) {
                throw new AgentException(ErrorCode.INTERNAL, "path");
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, json(note), StandardCharsets.UTF_8);
            snapshot.accept(note);
            return null;
        });
    }

    private static void requireSegment(String value, String label) {
        if (value == null
                || value.isBlank()
                || ".".equals(value)
                || value.contains("..")
                || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf('\0') >= 0) {
            throw new AgentException(ErrorCode.INTERNAL, label);
        }
    }

    private static String json(CorrectionNote note) {
        return "{"
                + "\"noteId\":\"" + escape(note.noteId()) + "\","
                + "\"sessionId\":\"" + escape(note.sessionId()) + "\","
                + "\"executionId\":\"" + escape(note.executionId()) + "\","
                + "\"createdAt\":\"" + escape(note.createdAt().toString()) + "\","
                + "\"source\":\"" + escape(note.source().name()) + "\","
                + "\"text\":\"" + escape(note.text()) + "\""
                + "}";
    }

    private static String escape(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.toString();
    }
}
