package dev.jasagent.rag;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class IndexStore {
    static final String FILE_NAME = "chunks.v1";
    private final Path userDirectory;
    private final Path workspaceDirectory;

    IndexStore(Path userDirectory, Path workspaceDirectory) {
        this.userDirectory = userDirectory;
        this.workspaceDirectory = workspaceDirectory;
    }

    int rebuild(RagScope scope) throws IOException {
        Path docs = docs(scope);
        List<IndexedChunk> chunks = new ArrayList<>();
        if (Files.isDirectory(docs)) {
            try (Stream<Path> walk = Files.walk(docs)) {
                List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
                for (Path file : files) {
                    if (!corpus(file)) {
                        continue;
                    }
                    String uri = docs.relativize(file).toString().replace('\\', '/');
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    for (TextChunk piece : Chunker.chunk(uri, text)) {
                        chunks.add(new IndexedChunk(
                                scope,
                                uri,
                                uri,
                                scope.wire() + ":" + piece.chunkId(),
                                piece.title(),
                                piece.start(),
                                piece.end(),
                                Hashes.sha256(piece.content()),
                                piece.content()));
                    }
                }
            }
        }
        write(indexFile(scope), chunks);
        return chunks.size();
    }

    List<IndexedChunk> read(RagScope scope) throws IOException {
        Path file = indexFile(scope);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !"v1".equals(lines.get(0))) {
            throw new AgentException(ErrorCode.INTERNAL, "索引版本无法读取");
        }
        List<IndexedChunk> chunks = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 8) {
                throw new AgentException(ErrorCode.INTERNAL, "索引行无法读取");
            }
            chunks.add(new IndexedChunk(
                    scope,
                    unescape(fields[2]),
                    unescape(fields[0]),
                    unescape(fields[1]),
                    unescape(fields[3]),
                    Integer.parseInt(fields[4]),
                    Integer.parseInt(fields[5]),
                    unescape(fields[6]),
                    unescape(fields[7])));
        }
        return chunks;
    }

    private void write(Path file, List<IndexedChunk> chunks) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder body = new StringBuilder("v1\n");
        for (IndexedChunk chunk : chunks) {
            body.append(escape(chunk.docId())).append('\t')
                    .append(escape(chunk.chunkId())).append('\t')
                    .append(escape(chunk.uri())).append('\t')
                    .append(escape(chunk.title())).append('\t')
                    .append(chunk.start()).append('\t')
                    .append(chunk.end()).append('\t')
                    .append(escape(chunk.contentHash())).append('\t')
                    .append(escape(chunk.content()))
                    .append('\n');
        }
        Path temporary = file.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(temporary, body.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path indexFile(RagScope scope) {
        return root(scope).resolve("rag").resolve("index").resolve(FILE_NAME);
    }

    private Path docs(RagScope scope) {
        return root(scope).resolve("rag").resolve("docs");
    }

    private Path root(RagScope scope) {
        return scope == RagScope.WORKSPACE ? workspaceDirectory : userDirectory;
    }

    private static boolean corpus(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".txt");
    }

    static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static String unescape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                switch (next) {
                    case '\\' -> out.append('\\');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    default -> out.append(next);
                }
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
