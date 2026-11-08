package dev.jasagent.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class Corpus {
    private final List<Chunk> chunks;

    private Corpus(List<Chunk> chunks) {
        this.chunks = List.copyOf(chunks);
    }

    public static Corpus empty() {
        return new Corpus(List.of());
    }

    public static Corpus index(Path docs, String scope) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        try (var files = Files.list(docs)) {
            for (Path file : files.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".md") || name.endsWith(".txt");
            }).sorted().toList()) {
                String text = Files.readString(file);
                int part = 0;
                for (Chunk chunk : Chunker.split(text)) {
                    if (chunk.text().isBlank()) {
                        continue;
                    }
                    chunks.add(new Chunk(file.getFileName().toString(), file.getFileName() + "-" + part, chunk.text(), chunk.start(), chunk.end(), scope, file.toAbsolutePath().toString()));
                    part++;
                }
            }
        }
        return new Corpus(chunks);
    }

    public Corpus plus(Corpus other) {
        List<Chunk> all = new ArrayList<>(chunks);
        all.addAll(other.chunks);
        return new Corpus(all);
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes()));
        } catch (Exception ex) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
