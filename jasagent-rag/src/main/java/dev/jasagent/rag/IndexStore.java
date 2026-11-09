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

/**
 * 按范围读写本地段索引。
 * 文档放在范围根目录的 rag/docs，索引放在 rag/index；写入先落临时文件再替换正式文件。
 */
final class IndexStore {
    /** 索引文件名。文件第一行是版本 v1，后面每行是一段。 */
    static final String FILE_NAME = "chunks.v1";
    private final Path userDirectory;
    private final Path workspaceDirectory;

    /**
     * 记住用户级和工作区级语料各自的根目录。
     *
     * @param userDirectory 用户范围的根，其下会使用 rag/docs 和 rag/index
     * @param workspaceDirectory 工作区范围的根，目录布局与用户范围相同
     */
    IndexStore(Path userDirectory, Path workspaceDirectory) {
        this.userDirectory = userDirectory;
        this.workspaceDirectory = workspaceDirectory;
    }

    /**
     * 扫描一个范围 docs 里的 Markdown 和纯文本，切段后整份重写索引。
     * 副作用：创建索引目录并替换该范围的索引文件；docs 不存在时写成空索引。
     *
     * @param scope 要重建的范围
     * @return 写入索引的段数
     */
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

    /**
     * 读回一个范围已经建好的索引。
     * 文件不存在视为还没建过；版本行不是 v1 或列数不是 8 列时拒绝读取。
     *
     * @param scope 要读取的范围
     * @return 索引中的段；还没有索引文件时为空列表
     */
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

    /**
     * 把段写成 v1 索引：先写旁边的临时文件，再尽量原子地替换正式文件。
     * 副作用：创建父目录，写出临时文件，并在替换成功后留下正式索引。文件系统不支持原子移动时退回普通替换。
     *
     * @param file 正式索引文件
     * @param chunks 要写入的段，顺序就是文件中的行序
     */
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

    /**
     * 定位某个范围的正式索引文件。
     *
     * @param scope 用户或工作区范围
     * @return 该范围根目录下 rag/index/chunks.v1
     */
    private Path indexFile(RagScope scope) {
        return root(scope).resolve("rag").resolve("index").resolve(FILE_NAME);
    }

    /**
     * 定位某个范围存放原始文档的目录。
     *
     * @param scope 用户或工作区范围
     * @return 该范围根目录下的 rag/docs
     */
    private Path docs(RagScope scope) {
        return root(scope).resolve("rag").resolve("docs");
    }

    /**
     * 选择某个范围对应的根目录。
     *
     * @param scope 用户或工作区范围
     * @return 工作区范围用工作区根目录，否则用用户根目录
     */
    private Path root(RagScope scope) {
        return scope == RagScope.WORKSPACE ? workspaceDirectory : userDirectory;
    }

    /**
     * 判断文件是不是会进入索引的语料。
     * 只看文件名后缀，不看文件内容。
     *
     * @param file 扫描到的普通文件
     * @return 文件名以 .md 或 .txt 结尾时为真，大小写不敏感
     */
    private static boolean corpus(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".txt");
    }

    /**
     * 把会打断索引行的字符换成反斜杠转义，使一行仍然对应一段。
     * 反斜杠、换行、回车和制表符会被转义，其余字符原样保留。
     *
     * @param raw 字段原文
     * @return 可以安全放进制表符分隔行的文本
     */
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

    /**
     * 把索引字段里的反斜杠转义还原成原文。
     * 识别反斜杠本身、换行、回车和制表符；不认识的转义只留下反斜杠后面的那个字符。
     *
     * @param raw 从索引行拆出来的字段
     * @return 还原后的字段原文
     */
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
