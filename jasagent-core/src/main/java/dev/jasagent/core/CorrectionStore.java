package dev.jasagent.core;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.workflow.CorrectionNote;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class CorrectionStore {
    public void append(Path userDirectory, CorrectionNote note, Consumer<CorrectionNote> snapshot) throws Exception {
        Path file = userDirectory.resolve("corrections").resolve(note.sessionId()).resolve(note.noteId() + ".json");
        Files.createDirectories(file.getParent());
        String json = "{\"noteId\":\"" + note.noteId() + "\",\"text\":\"" + note.text() + "\",\"source\":\"" + note.source() + "\"}";
        Files.writeString(file, json, StandardCharsets.UTF_8);
        snapshot.accept(note);
        VirtualIo.executor();
    }
}
