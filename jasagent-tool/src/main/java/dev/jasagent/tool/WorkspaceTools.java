package dev.jasagent.tool;

import dev.jasagent.api.llm.ToolDefinition;
import dev.jasagent.api.tool.ReadOnlyTools;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class WorkspaceTools {
    private static final Set<String> SKIP = Set.of(
            ".git", "node_modules", "target", "dist", "build", ".gradle", ".idea",
            "__pycache__", ".venv", "venv", ".jasagent", "coverage", ".next", "out");
    private static final int MAX_FILES = 100_000;
    private static final int MAX_ENTRIES = 200;
    private static final int MAX_MATCHES = 40;
    private static final int MAX_GLOBS = 200;
    private static final int MAX_READ_LINES = 200;
    private static final int MAX_READ_CHARS = 64_000;
    private static final int MAX_BATCH = 8;

    private WorkspaceTools() {
    }

    public static List<ToolDefinition> definitions(List<String> names) {
        List<ToolDefinition> tools = new ArrayList<>();
        if (names == null) {
            return tools;
        }
        for (String name : names) {
            ToolDefinition definition = definition(name);
            if (definition != null) {
                tools.add(definition);
            }
        }
        return List.copyOf(tools);
    }

    public static boolean supports(String name) {
        return ReadOnlyTools.is(name) || "run_shell_command".equals(name) || "clock".equals(name);
    }

    public static boolean runsWithoutApproval(String name, String arguments) {
        if (ReadOnlyTools.is(name) || "clock".equals(name)) {
            return true;
        }
        if (!"run_shell_command".equals(name)) {
            return false;
        }
        String command = command(arguments);
        return command.isBlank() || ShellCommands.readOnly(command);
    }

    public static String execute(Path workspace, String name, String arguments) {
        try {
            Path root = workspace.toAbsolutePath().normalize();
            return switch (name) {
                case "list_directory" -> listDirectory(root, arguments);
                case "glob_files" -> globFiles(root, arguments);
                case "grep_files" -> grepFiles(root, arguments);
                case "search_files" -> searchFiles(root, arguments);
                case "search_workspace" -> searchWorkspace(root, arguments);
                case "read_file" -> readFile(root, arguments);
                case "read_files" -> readFiles(root, arguments);
                case "run_shell_command" -> runShell(root, arguments);
                default -> "未知工具 " + name;
            };
        } catch (RuntimeException | IOException ex) {
            return "错误: " + (ex.getMessage() == null ? "工具失败" : ex.getMessage());
        }
    }

    private static ToolDefinition definition(String name) {
        return switch (name) {
            case "clock" -> new ToolDefinition("clock", "返回当前 UTC 时间。用户问现在几点时使用。", object());
            case "list_directory" -> new ToolDefinition(
                    "list_directory",
                    "列出工作区目录，并给出该目录下的文件总数。用户问工作区有多少文件时，path 传 .，回答必须使用结果里的文件总数。",
                    object("path", "相对工作区的目录，默认 ."));
            case "glob_files" -> new ToolDefinition(
                    "glob_files",
                    "按文件名 glob 查找工作区文件，例如 **/*.java。",
                    object("glob_pattern", "glob，如 **/*.java", "path", "搜索根目录，相对工作区，默认 ."));
            case "grep_files" -> new ToolDefinition(
                    "grep_files",
                    "在工作区文件内容里搜索。pattern 是要找的文本或正则。",
                    object("pattern", "要搜索的内容", "path", "搜索根目录，相对工作区，默认 .", "file_glob", "可选，只搜匹配的文件名，如 *.java"));
            case "search_files" -> new ToolDefinition(
                    "search_files",
                    "按文件名关键词查找工作区文件。",
                    object("query", "文件名里的关键词", "path", "搜索根目录，相对工作区，默认 ."));
            case "search_workspace" -> new ToolDefinition(
                    "search_workspace",
                    "同时按文件名和文件内容搜索工作区。",
                    object("query", "关键词", "path", "搜索根目录，相对工作区，默认 ."));
            case "read_file" -> new ToolDefinition(
                    "read_file",
                    "读取工作区里的一个文本文件。大文件只返回指定行段。",
                    object("path", "相对工作区的文件路径", "start_line", "起始行，从 1 开始", "end_line", "结束行，含本行；0 表示到末尾"));
            case "read_files" -> new ToolDefinition(
                    "read_files",
                    "一次读取工作区里最多 8 个文本文件。paths 是相对路径数组。",
                    object("paths", "相对路径，逗号分隔或 JSON 数组", "start_line", "每个文件的起始行", "end_line", "每个文件的结束行，0 表示到末尾"));
            case "run_shell_command" -> new ToolDefinition(
                    "run_shell_command",
                    "在工作区里执行只读 shell 命令，例如 find . -type f | wc -l。不要用它代替 read_file、list_directory、grep_files。删除、写入、安装和 git 提交不会直接执行。",
                    object("command", "要执行的命令", "working_directory", "相对工作区的目录，默认 ."));
            default -> null;
        };
    }

    private static String object(String... keys) {
        StringBuilder json = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        for (int index = 0; index < keys.length; index += 2) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(keys[index]).append("\":{\"type\":\"string\",\"description\":\"")
                    .append(keys[index + 1].replace("\"", "'"))
                    .append("\"}");
        }
        json.append("}}");
        return json.toString();
    }

    private static String listDirectory(Path root, String arguments) throws IOException {
        Map<String, Object> values = ToolJson.parse(arguments);
        String raw = ToolJson.text(values, "path");
        if (raw.isBlank() && arguments != null && !arguments.isBlank() && !arguments.trim().startsWith("{")) {
            raw = arguments.trim();
        }
        Path dir = resolve(root, raw.isBlank() ? "." : raw);
        if (!Files.isDirectory(dir)) {
            return "错误: 不是目录 " + display(root, dir);
        }
        int files = countFiles(dir);
        List<String> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(MAX_ENTRIES)
                    .forEach(path -> entries.add(path.getFileName() + (Files.isDirectory(path) ? "/" : "")));
        }
        StringBuilder text = new StringBuilder();
        text.append("文件总数是 ").append(files).append("。\n");
        text.append("fileCount: ").append(files).append('\n');
        text.append("path: ").append(display(root, dir)).append('\n');
        text.append("这个数字不含 .git、node_modules、target、dist、build。回答文件数量时只用 fileCount。\n");
        text.append("entries:\n");
        for (String entry : entries) {
            text.append(entry).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private static String globFiles(Path root, String arguments) throws IOException {
        Map<String, Object> values = ToolJson.parse(arguments);
        String pattern = first(values, "glob_pattern", "pattern");
        if (pattern.isBlank()) {
            return "错误: glob_pattern 必填";
        }
        Path start = resolve(root, blank(ToolJson.text(values, "path"), "."));
        String glob = pattern.contains("/") ? pattern : "**/" + pattern;
        List<String> hits = new ArrayList<>();
        walk(start, (file, relative) -> {
            if (hits.size() >= MAX_GLOBS) {
                return false;
            }
            if (globMatch(relative, glob)) {
                hits.add(relative);
            }
            return true;
        });
        StringBuilder text = new StringBuilder();
        text.append("glob: ").append(pattern).append('\n');
        text.append("count: ").append(hits.size()).append('\n');
        for (String hit : hits) {
            text.append(hit).append('\n');
        }
        if (hits.isEmpty()) {
            text.append("没有匹配文件。\n");
        }
        return text.toString().stripTrailing();
    }

    private static String grepFiles(Path root, String arguments) throws IOException {
        Map<String, Object> values = ToolJson.parse(arguments);
        String patternText = ToolJson.text(values, "pattern");
        if (patternText.isBlank() && arguments != null && !arguments.isBlank() && !arguments.trim().startsWith("{")) {
            patternText = arguments.trim();
        }
        if (patternText.isBlank()) {
            return "错误: pattern 必填";
        }
        String pattern = patternText;
        Path start = resolve(root, blank(ToolJson.text(values, "path"), "."));
        String fileGlob = ToolJson.text(values, "file_glob");
        String filePattern = fileGlob.isBlank() ? "" : (fileGlob.contains("/") ? fileGlob : "**/" + fileGlob);
        Pattern compiled = compile(pattern);
        List<String> hits = new ArrayList<>();
        walk(start, (file, relative) -> {
            if (hits.size() >= MAX_MATCHES) {
                return false;
            }
            if (!filePattern.isBlank() && !globMatch(relative, filePattern)) {
                return true;
            }
            hits.addAll(grepFile(file, relative, pattern, compiled, MAX_MATCHES - hits.size()));
            return hits.size() < MAX_MATCHES;
        });
        StringBuilder text = new StringBuilder();
        text.append("pattern: ").append(pattern).append('\n');
        text.append("matches: ").append(hits.size()).append('\n');
        for (String hit : hits) {
            text.append(hit).append('\n');
        }
        if (hits.isEmpty()) {
            text.append("没有匹配内容。\n");
        }
        return text.toString().stripTrailing();
    }

    private static String searchFiles(Path root, String arguments) throws IOException {
        String query = query(arguments);
        if (query.isBlank()) {
            return "错误: query 必填";
        }
        Path start = resolve(root, blank(ToolJson.text(ToolJson.parse(arguments), "path"), "."));
        String needle = query.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        walk(start, (file, relative) -> {
            if (hits.size() >= MAX_GLOBS) {
                return false;
            }
            if (file.getFileName().toString().toLowerCase(Locale.ROOT).contains(needle)
                    || relative.toLowerCase(Locale.ROOT).contains(needle)) {
                hits.add(relative);
            }
            return true;
        });
        return joinHits("query: " + query, hits);
    }

    private static String searchWorkspace(Path root, String arguments) throws IOException {
        String query = query(arguments);
        String encoded = "\"" + query.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        String names = searchFiles(root, "{\"query\":" + encoded + "}");
        String contents = grepFiles(root, "{\"pattern\":" + encoded + "}");
        return "文件名\n" + names + "\n内容\n" + contents;
    }

    private static String readFile(Path root, String arguments) {
        Map<String, Object> values = ToolJson.parse(arguments);
        String raw = ToolJson.text(values, "path");
        if (raw.isBlank() && arguments != null && !arguments.isBlank() && !arguments.trim().startsWith("{")) {
            raw = arguments.trim();
        }
        if (raw.isBlank()) {
            return "错误: path 必填";
        }
        return readOne(root, raw, ToolJson.number(values, "start_line", 1), ToolJson.number(values, "end_line", 0));
    }

    private static String readFiles(Path root, String arguments) {
        Map<String, Object> values = ToolJson.parse(arguments);
        List<String> paths = ToolJson.strings(values, "paths");
        if (paths.isEmpty()) {
            return "错误: paths 必填";
        }
        if (paths.size() > MAX_BATCH) {
            return "错误: 一次最多读 " + MAX_BATCH + " 个文件";
        }
        int start = ToolJson.number(values, "start_line", 1);
        int end = ToolJson.number(values, "end_line", 0);
        StringBuilder text = new StringBuilder();
        for (String path : paths) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(readOne(root, path, start, end));
        }
        return text.toString();
    }

    private static String runShell(Path root, String arguments) {
        Map<String, Object> values = ToolJson.parse(arguments);
        String command = ToolJson.text(values, "command");
        if (command.isBlank() && arguments != null && !arguments.trim().startsWith("{")) {
            command = arguments.trim();
        }
        return ShellCommands.run(root, command, ToolJson.text(values, "working_directory"));
    }

    private static String readOne(Path root, String raw, int startLine, int endLine) {
        try {
            Path file = resolve(root, raw);
            if (!Files.isRegularFile(file)) {
                return "错误: 不是文件 " + display(root, file);
            }
            if (!probablyText(file)) {
                return "错误: 不是文本文件 " + display(root, file);
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = Math.max(1, startLine);
            int end = endLine <= 0 ? lines.size() : Math.min(lines.size(), endLine);
            if (end < start) {
                end = Math.min(lines.size(), start);
            }
            if (end - start + 1 > MAX_READ_LINES) {
                end = start + MAX_READ_LINES - 1;
            }
            StringBuilder text = new StringBuilder();
            text.append("path: ").append(display(root, file)).append('\n');
            text.append("lines: ").append(start).append('-').append(Math.min(end, lines.size())).append('\n');
            int chars = 0;
            for (int line = start; line <= end && line <= lines.size(); line++) {
                String row = line + "|" + lines.get(line - 1);
                if (chars + row.length() > MAX_READ_CHARS) {
                    text.append("...后面的内容省略\n");
                    break;
                }
                text.append(row).append('\n');
                chars += row.length();
            }
            return text.toString().stripTrailing();
        } catch (CharacterCodingException ex) {
            return "错误: 不是文本文件 " + raw;
        } catch (IOException ex) {
            return "错误: 读不到 " + raw;
        } catch (IllegalArgumentException ex) {
            return "错误: " + ex.getMessage();
        }
    }

    private static int countFiles(Path root) throws IOException {
        int[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && skip(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
                    count[0]++;
                    if (count[0] >= MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    private static void walk(Path start, Walker walker) throws IOException {
        if (!Files.exists(start)) {
            return;
        }
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(start) && skip(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = start.relativize(file).toString().replace('\\', '/');
                try {
                    return walker.accept(file, relative) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                } catch (IOException ex) {
                    return FileVisitResult.CONTINUE;
                }
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static List<String> grepFile(Path file, String relative, String literal, Pattern compiled, int room) throws IOException {
        List<String> hits = new ArrayList<>();
        if (room <= 0 || Files.size(file) > 1_000_000 || !probablyText(file)) {
            return hits;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size() && hits.size() < room; index++) {
            String line = lines.get(index);
            String sample = line.length() > 2000 ? line.substring(0, 2000) : line;
            boolean matched = compiled != null ? compiled.matcher(sample).find() : sample.contains(literal);
            if (matched) {
                hits.add(relative + ":" + (index + 1) + ":" + sample.strip());
            }
        }
        return hits;
    }

    private static boolean globMatch(String relative, String glob) {
        String normalized = relative.replace('\\', '/');
        return normalized.matches(globRegex(glob)) || Path.of(normalized).getFileName().toString().matches(globRegex(nameGlob(glob)));
    }

    private static String nameGlob(String glob) {
        int slash = glob.lastIndexOf('/');
        return slash >= 0 ? glob.substring(slash + 1) : glob;
    }

    private static String globRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < glob.length(); index++) {
            char current = glob.charAt(index);
            if (current == '*') {
                if (index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                    regex.append(".*");
                    index++;
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '/') {
                        index++;
                    }
                } else {
                    regex.append("[^/]*");
                }
                continue;
            }
            if (current == '?') {
                regex.append("[^/]");
                continue;
            }
            if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
                regex.append('\\');
            }
            regex.append(current);
        }
        return regex.toString();
    }

    private static Pattern compile(String pattern) {
        if (pattern.length() > 200) {
            return null;
        }
        boolean regex = false;
        for (int index = 0; index < pattern.length(); index++) {
            if ("\\.^$*+?{}[]|()".indexOf(pattern.charAt(index)) >= 0) {
                regex = true;
                break;
            }
        }
        if (!regex) {
            return null;
        }
        try {
            return Pattern.compile(pattern);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean probablyText(Path file) {
        try {
            byte[] sample = Files.size(file) > 512 ? readSample(file) : Files.readAllBytes(file);
            for (byte value : sample) {
                if (value == 0) {
                    return false;
                }
            }
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            decoder.decode(java.nio.ByteBuffer.wrap(sample));
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static byte[] readSample(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            return input.readNBytes(512);
        }
    }

    private static boolean skip(Path dir) {
        Path name = dir.getFileName();
        return name != null && SKIP.contains(name.toString());
    }

    private static Path resolve(Path root, String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0 || raw.startsWith("~")) {
            throw new IllegalArgumentException("路径超出工作区");
        }
        Path candidate = Path.of(raw.trim());
        if (candidate.isAbsolute()) {
            throw new IllegalArgumentException("路径超出工作区");
        }
        Path resolved = root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("路径超出工作区");
        }
        return resolved;
    }

    private static String display(Path root, Path path) {
        if (path.equals(root)) {
            return ".";
        }
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String command(String arguments) {
        Map<String, Object> values = ToolJson.parse(arguments);
        String command = ToolJson.text(values, "command");
        if (!command.isBlank()) {
            return command;
        }
        if (arguments == null || arguments.trim().startsWith("{")) {
            return "";
        }
        return arguments.trim();
    }

    private static String query(String arguments) {
        Map<String, Object> values = ToolJson.parse(arguments);
        String query = first(values, "query", "pattern");
        if (!query.isBlank()) {
            return query;
        }
        if (arguments != null && !arguments.isBlank() && !arguments.trim().startsWith("{")) {
            return arguments.trim();
        }
        return "";
    }

    private static String first(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String value = ToolJson.text(values, key);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String joinHits(String title, List<String> hits) {
        StringBuilder text = new StringBuilder();
        text.append(title).append('\n');
        text.append("count: ").append(hits.size()).append('\n');
        for (String hit : hits) {
            text.append(hit).append('\n');
        }
        if (hits.isEmpty()) {
            text.append("没有匹配文件。\n");
        }
        return text.toString().stripTrailing();
    }

    @FunctionalInterface
    private interface Walker {
        boolean accept(Path file, String relative) throws IOException;
    }
}
