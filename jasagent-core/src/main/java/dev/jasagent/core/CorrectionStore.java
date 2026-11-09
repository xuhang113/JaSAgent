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

/**
 * 把订正笔记写成用户目录下按会话和笔记标识分开的 JSON 文件，并通知快照。
 */
public final class CorrectionStore {
    /**
     * 校验笔记字段和路径安全后，把 JSON 写到 corrections/会话/笔记标识.json，再把同一条笔记交给快照。副作用是创建目录并写文件。
     *
     * @param userDirectory 用户的 jasagent 目录
     * @param note 要保存的订正，标识和会话不能含路径成分
     * @param snapshot 文件写成功后接收这条笔记的回调
     * @return 文件和快照都完成后的阶段
     */
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

    /**
     * 拒绝空白、点、父目录、分隔符和空字符，避免笔记路径逃出 corrections 目录。不符合时抛出内部错误。副作用无，只做校验。
     *
     * @param value 路径中的一段
     * @param label 失败时用来指出是笔记标识还是会话标识
     */
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

    /**
     * 把订正的标识、会话、执行、时间、来源和正文收成一段 JSON。
     *
     * @param note 已经校验过的订正
     * @return 单行 JSON 对象
     */
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

    /**
     * 转义 JSON 字符串中的反斜杠、引号、换行、回车、制表符和其他控制字符。
     *
     * @param value 原始文本
     * @return 可放进 JSON 字符串的文本
     */
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
