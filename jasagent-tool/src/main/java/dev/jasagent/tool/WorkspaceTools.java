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

/**
 * 在工作区边界内列目录、按名字和内容搜索、读取文本，并执行只读 shell。
 */
public final class WorkspaceTools {
    /**
     * 遍历和计数时整棵跳过的目录名，避免把版本库、依赖和构建产物算进工作区。
     */
    private static final Set<String> SKIP = Set.of(
            ".git", "node_modules", "target", "dist", "build", ".gradle", ".idea",
            "__pycache__", ".venv", "venv", ".jasagent", "coverage", ".next", "out");
    /**
     * 一次数文件时最多累计的文件数，到顶就停止遍历。
     */
    private static final int MAX_FILES = 100_000;
    /**
     * 列目录时最多返回的直接子项数。
     */
    private static final int MAX_ENTRIES = 200;
    /**
     * 内容搜索最多保留的命中行数。
     */
    private static final int MAX_MATCHES = 40;
    /**
     * 按文件名查找时最多保留的文件数。
     */
    private static final int MAX_GLOBS = 200;
    /**
     * 读取一个文件时最多返回的行数。
     */
    private static final int MAX_READ_LINES = 200;
    /**
     * 读取一个文件时最多累计的字符数，超出后省略后面的行。
     */
    private static final int MAX_READ_CHARS = 64_000;
    /**
     * 一次批量读取最多接受的文件数。
     */
    private static final int MAX_BATCH = 8;

    /**
     * 禁止实例化。工作区工具都通过静态方法使用。
     */
    private WorkspaceTools() {
    }

    /**
     * 按给定名字的顺序取出模型可见的工具定义，不认识的名字跳过。
     *
     * @param names 要暴露的工具名；null 当成空名单
     * @return 能对上的工具定义
     */
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

    /**
     * 判断这个名字是不是工作区知道如何定义或执行的工具。
     *
     * @param name 工具名
     * @return 只读工具、shell 或时钟时为 true
     */
    public static boolean supports(String name) {
        return ReadOnlyTools.is(name) || "run_shell_command".equals(name) || "clock".equals(name);
    }

    /**
     * 判断这次调用能不能直接执行。只读工具和时钟可以；shell 只有命令为空或判定为只读时可以。
     *
     * @param name 工具名
     * @param arguments 调用参数原文
     * @return 不需要额外批准时为 true
     */
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

    /**
     * 在工作区里执行一个工具。路径越界和读写失败都收成错误说明。
     *
     * @param workspace 工作区根目录
     * @param name 工具名
     * @param arguments 参数原文，通常是 JSON
     * @return 给模型看的结果文本；不认识的工具返回未知工具说明
     */
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

    /**
     * 按名字给出工具定义和参数说明。
     *
     * @param name 工具名
     * @return 对应定义；没有该工具时为 null
     */
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

    /**
     * 把成对的参数名和说明收成一个对象型 JSON Schema。
     *
     * @param keys 交替排列的参数名和说明
     * @return 工具参数的 JSON Schema 文本
     */
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

    /**
     * 列出目录的直接子项，并给出不含依赖和构建目录的文件总数。
     *
     * @param root 工作区根
     * @param arguments 含 path 的参数；不是 JSON 时把整段当成路径
     * @return 文件总数、路径和子项清单
     */
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

    /**
     * 按 glob 找文件名，结果最多保留上限条。
     *
     * @param root 工作区根
     * @param arguments 含 glob_pattern 和可选 path 的参数
     * @return 匹配数量和相对路径；没有匹配时说明没有匹配文件
     */
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

    /**
     * 在文件内容里找文本或正则，还可以用文件名 glob 收窄范围。
     *
     * @param root 工作区根
     * @param arguments 含 pattern、可选 path 和 file_glob 的参数
     * @return 命中的文件、行号和行内容
     */
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

    /**
     * 按文件名或相对路径里的关键词找文件。
     *
     * @param root 工作区根
     * @param arguments 含 query 和可选 path 的参数
     * @return 命中的相对路径和数量
     */
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

    /**
     * 用同一个关键词先搜文件名，再搜文件内容，并把两段结果接在一起。
     *
     * @param root 工作区根
     * @param arguments 含 query 的参数
     * @return 文件名搜索和内容搜索拼成的文本
     */
    private static String searchWorkspace(Path root, String arguments) throws IOException {
        String query = query(arguments);
        String encoded = "\"" + query.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        String names = searchFiles(root, "{\"query\":" + encoded + "}");
        String contents = grepFiles(root, "{\"pattern\":" + encoded + "}");
        return "文件名\n" + names + "\n内容\n" + contents;
    }

    /**
     * 读取一个文本文件的指定行段。
     *
     * @param root 工作区根
     * @param arguments 含 path、start_line 和 end_line 的参数
     * @return 带路径和行号的文本，或错误说明
     */
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

    /**
     * 按同一行段读取最多 8 个文本文件。
     *
     * @param root 工作区根
     * @param arguments 含 paths 和可选行段的参数
     * @return 各文件结果用空行隔开的文本
     */
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

    /**
     * 解析命令和工作目录后，在工作区里执行 shell。
     *
     * @param root 工作区根
     * @param arguments 含 command 和可选 working_directory 的参数
     * @return shell 的退出码和输出，或错误说明
     */
    private static String runShell(Path root, String arguments) {
        Map<String, Object> values = ToolJson.parse(arguments);
        String command = ToolJson.text(values, "command");
        if (command.isBlank() && arguments != null && !arguments.trim().startsWith("{")) {
            command = arguments.trim();
        }
        return ShellCommands.run(root, command, ToolJson.text(values, "working_directory"));
    }

    /**
     * 读取单个文本文件。越界、不是文本或读不到时返回错误说明；过长时按行数和字符数截断。
     *
     * @param root 工作区根
     * @param raw 相对工作区的路径
     * @param startLine 起始行，从 1 计；小于 1 时从第 1 行开始
     * @param endLine 结束行，含本行；小于等于 0 表示读到末尾
     * @return 带行号的文本或错误说明
     */
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

    /**
     * 统计目录下的普通文件数，跳过依赖和构建目录，到上限就停止。
     *
     * @param root 开始统计的目录
     * @return 数到的文件数
     */
    private static int countFiles(Path root) throws IOException {
        int[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            /**
             * 进入子目录前跳过依赖和构建目录，统计起点本身继续往下走。
             *
             * @param dir 即将进入的目录
             * @param attrs 该目录的属性
             * @return 要跳过的目录返回跳过子树，否则继续
             */
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && skip(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            /**
             * 把普通文件计入总数，达到上限时停止整次遍历。
             *
             * @param file 当前文件
             * @param attrs 该文件的属性
             * @return 未到上限时继续，到上限时终止遍历
             */
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

            /**
             * 单个路径访问失败时继续统计其余文件。
             *
             * @param file 访问失败的路径
             * @param exc 访问失败的原因
             * @return 继续遍历
             */
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    /**
     * 遍历起点下的普通文件，把相对路径交给访问器；访问器返回 false 时停止。
     *
     * @param start 遍历起点；路径不存在时直接返回
     * @param walker 处理每个文件的回调
     */
    private static void walk(Path start, Walker walker) throws IOException {
        if (!Files.exists(start)) {
            return;
        }
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            /**
             * 进入子目录前跳过依赖和构建目录，遍历起点本身继续。
             *
             * @param dir 即将进入的目录
             * @param attrs 该目录的属性
             * @return 要跳过的目录返回跳过子树，否则继续
             */
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(start) && skip(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            /**
             * 把相对路径交给访问器。符号链接跳过；访问器返回 false 时停止，读写失败时跳过该文件。
             *
             * @param file 当前文件
             * @param attrs 该文件的属性
             * @return 继续、终止，或在该文件失败时继续
             */
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

            /**
             * 单个路径访问失败时继续看其余文件。
             *
             * @param file 访问失败的路径
             * @param exc 访问失败的原因
             * @return 继续遍历
             */
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 在一个文本文件里找字面量或正则，最多再收给定条数。过大或像二进制的文件直接跳过。
     *
     * @param file 要搜索的文件
     * @param relative 相对起点的路径，写进命中行
     * @param literal 没有可用正则时按包含关系查找的文本
     * @param compiled 已编译的正则；null 表示按字面量查找
     * @param room 还能再收的命中数
     * @return 本文件的命中行
     */
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

    /**
     * 判断相对路径或其中的文件名是否匹配 glob。双星号可以跨目录，单星号不跨斜杠。
     *
     * @param relative 用斜杠分隔的相对路径
     * @param glob glob 模式
     * @return 路径或文件名匹配时为 true
     */
    private static boolean globMatch(String relative, String glob) {
        String normalized = relative.replace('\\', '/');
        return normalized.matches(globRegex(glob)) || Path.of(normalized).getFileName().toString().matches(globRegex(nameGlob(glob)));
    }

    /**
     * 取 glob 的最后一段，用来只对文件名做匹配。
     *
     * @param glob 完整 glob
     * @return 最后一个斜杠后面的模式；没有斜杠时就是原模式
     */
    private static String nameGlob(String glob) {
        int slash = glob.lastIndexOf('/');
        return slash >= 0 ? glob.substring(slash + 1) : glob;
    }

    /**
     * 把 glob 收成正则。星号和问号按路径段处理，其余正则特殊字符转义。
     *
     * @param glob glob 模式
     * @return 可对整段路径匹配的正则文本
     */
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

    /**
     * 搜索文本含正则特殊字符且不超过长度上限时编译成模式；否则让调用方按字面量查找。
     *
     * @param pattern 用户给的搜索文本
     * @return 编译成功的模式；应按字面量处理或编译失败时为 null
     */
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

    /**
     * 看文件开头有没有空字节，并要求这段字节能按 UTF-8 解码。
     *
     * @param file 要判断的文件
     * @return 像文本文件时为 true
     */
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

    /**
     * 只读文件开头的 512 字节，用来判断是不是文本。
     *
     * @param file 要抽样的文件
     * @return 最多 512 字节
     */
    private static byte[] readSample(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            return input.readNBytes(512);
        }
    }

    /**
     * 判断目录名是不是在跳过名单里。
     *
     * @param dir 当前目录
     * @return 目录名命中跳过名单时为 true
     */
    private static boolean skip(Path dir) {
        Path name = dir.getFileName();
        return name != null && SKIP.contains(name.toString());
    }

    /**
     * 把相对路径收成工作区内的绝对路径。空字节、家目录简写、绝对路径和逃出根目录都会被拒绝。
     *
     * @param root 工作区根
     * @param raw 用户给的相对路径
     * @return 落在工作区内的规范路径
     */
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

    /**
     * 把绝对路径显示成相对工作区的斜杠路径，根目录本身显示为一个点。
     *
     * @param root 工作区根
     * @param path 已经落在工作区内的路径
     * @return 写进结果文本的相对路径
     */
    private static String display(Path root, Path path) {
        if (path.equals(root)) {
            return ".";
        }
        return root.relativize(path).toString().replace('\\', '/');
    }

    /**
     * 从参数里取出要执行的命令。JSON 里有 command 就用它；不是对象的原文则整段当成命令。
     *
     * @param arguments 工具参数原文
     * @return 命令文本；没有命令时返回空串
     */
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

    /**
     * 从参数里取出搜索词，先认 query，再认 pattern；不是 JSON 时用整段原文。
     *
     * @param arguments 工具参数原文
     * @return 搜索词；没有时返回空串
     */
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

    /**
     * 按给定顺序返回第一个非空白的参数值。
     *
     * @param values 已解析的参数
     * @param keys 按优先级排列的键
     * @return 第一个有内容的值；都没有时返回空串
     */
    private static String first(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String value = ToolJson.text(values, key);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * 文本为空白时换成兜底值。
     *
     * @param value 原文本
     * @param fallback 空白时使用的文本
     * @return 原文本或兜底文本
     */
    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 把标题和命中列表收成带数量的文本。没有命中时加上没有匹配文件的说明。
     *
     * @param title 结果开头的标题行
     * @param hits 相对路径或命中行
     * @return 拼好的结果文本
     */
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

    /**
     * 遍历工作区时对每个普通文件做一次判断，用来决定要不要继续。
     */
    @FunctionalInterface
    private interface Walker {
        /**
         * 处理一个文件。返回 false 表示后面的文件不用再看。
         *
         * @param file 当前文件
         * @param relative 相对遍历起点、用斜杠分隔的路径
         * @return 继续遍历时为 true
         */
        boolean accept(Path file, String relative) throws IOException;
    }
}
