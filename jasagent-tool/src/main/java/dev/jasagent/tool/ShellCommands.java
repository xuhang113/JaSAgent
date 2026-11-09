package dev.jasagent.tool;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 判断一条 shell 命令能不能当作只读命令，并在工作区里执行它、截断输出、去掉环境中的密钥。
 */
final class ShellCommands {
    /**
     * 一条命令最多运行的秒数，到点后强制结束。
     */
    private static final int TIMEOUT_SECONDS = 20;
    /**
     * 保留的输出上限，按字节计，超出后停止继续读。
     */
    private static final int MAX_OUTPUT = 12_000;
    /**
     * 允许出现在管道每一段开头的命令名。
     */
    private static final Set<String> COMMANDS = Set.of(
            "ls", "find", "wc", "pwd", "echo", "stat", "file", "head", "tail",
            "rg", "grep", "egrep", "fgrep", "git", "cat", "cut", "sort", "uniq",
            "tr", "basename", "dirname", "realpath", "du", "df", "date", "uname",
            "which", "printf", "true", "false", "id", "whoami", "sed", "awk",
            "nl", "diff", "cmp", "comm", "column");
    /**
     * git 后面允许使用的只读子命令。
     */
    private static final Set<String> GIT = Set.of(
            "status", "log", "diff", "show", "rev-parse", "ls-files", "blame", "shortlog", "describe");

    /**
     * 禁止实例化。检查和执行都通过静态方法完成。
     */
    private ShellCommands() {
    }

    /**
     * 判断整条命令是不是由允许的只读命令用管道串起来。换行、命令替换、重定向、绝对路径，以及会改文件的 find 和 sed，都不算只读。
     *
     * @param command 待判断的命令文本
     * @return 可以不经额外批准执行时为 true
     */
    static boolean readOnly(String command) {
        if (command == null || command.isBlank() || command.length() > 4000) {
            return false;
        }
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0 || command.indexOf('\0') >= 0) {
            return false;
        }
        if (command.contains("`") || command.contains("$(") || command.contains("||") || command.contains("&&")) {
            return false;
        }
        if (command.contains(";") || command.contains("&") || command.contains(">") || command.contains("<") || command.contains("~")) {
            return false;
        }
        if (command.contains("..") || outsideWorkspace(command)) {
            return false;
        }
        String[] pipes = command.split("\\|");
        if (pipes.length == 0) {
            return false;
        }
        for (String pipe : pipes) {
            if (!simple(pipe.trim())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 在工作区或其中的相对目录执行命令。空命令、高危命令和工作目录越界时返回错误说明；否则返回退出码和截断后的输出。
     *
     * @param workspace 工作区根目录
     * @param command 要执行的命令
     * @param relativeDir 相对工作区的工作目录；空白表示就在根目录
     * @return 给模型看的退出码和输出，或一条错误说明
     */
    static String run(Path workspace, String command, String relativeDir) {
        if (command == null || command.isBlank()) {
            return "错误: 命令是空的";
        }
        if (catastrophic(command)) {
            return "错误: 拒绝执行高危命令";
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path cwd = root;
        if (relativeDir != null && !relativeDir.isBlank()) {
            Path resolved = root.resolve(relativeDir.trim()).normalize();
            if (!resolved.startsWith(root)) {
                return "错误: 工作目录超出工作区";
            }
            cwd = resolved;
        }
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        stripSecrets(builder.environment());
        Process process = null;
        try {
            process = builder.start();
            byte[] captured = readUntilExit(process);
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return "错误: 命令超时\n" + text(captured);
            }
            String output = text(captured);
            if (output.isBlank()) {
                return "exit: " + process.exitValue();
            }
            return "exit: " + process.exitValue() + "\n" + output;
        } catch (InterruptedException ex) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return "错误: 命令被中断";
        } catch (Exception ex) {
            if (process != null) {
                process.destroyForcibly();
            }
            return "错误: 命令没有跑起来";
        }
    }

    /**
     * 判断管道中的一段是不是白名单里的单个命令，并拒绝会删文件或就地改文件的 find 和 sed 写法。
     *
     * @param segment 去掉两端空白后的一段命令
     * @return 这一段可以放行时为 true
     */
    private static boolean simple(String segment) {
        if (segment.isBlank()) {
            return false;
        }
        String[] words = segment.split("\\s+");
        String name = words[0];
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            return false;
        }
        if (!COMMANDS.contains(name)) {
            return false;
        }
        if ("git".equals(name)) {
            if (words.length < 2 || !GIT.contains(words[1])) {
                return false;
            }
        }
        if ("find".equals(name)) {
            for (String word : words) {
                if ("-delete".equals(word) || "-exec".equals(word) || "-execdir".equals(word)
                        || "-ok".equals(word) || "-okdir".equals(word) || word.startsWith("-fprint")) {
                    return false;
                }
            }
        }
        if ("sed".equals(name)) {
            for (String word : words) {
                if ("-i".equals(word) || word.startsWith("--in-place")) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 判断命令里有没有以斜杠开头、因而会指向工作区外的路径。
     *
     * @param command 整条命令
     * @return 出现这种绝对路径时为 true
     */
    private static boolean outsideWorkspace(String command) {
        for (int index = 0; index < command.length(); index++) {
            if (command.charAt(index) != '/') {
                continue;
            }
            if (index == 0) {
                return true;
            }
            char previous = command.charAt(index - 1);
            if (Character.isWhitespace(previous) || previous == '"' || previous == '\'') {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断命令里有没有格式化磁盘、fork 炸弹或删除根目录这类高危写法。
     *
     * @param command 整条命令
     * @return 含高危片段时为 true
     */
    private static boolean catastrophic(String command) {
        String folded = command.toLowerCase(Locale.ROOT);
        return folded.contains("mkfs")
                || folded.contains(":(){")
                || folded.contains("rm -rf /")
                || folded.contains("rm -fr /");
    }

    /**
     * 从即将交给子进程的环境里去掉名称像密钥、口令或本项目配置的变量。
     *
     * @param environment 子进程环境，匹配的键会从中删除
     */
    private static void stripSecrets(Map<String, String> environment) {
        environment.keySet().removeIf(key -> {
            String upper = key.toUpperCase(Locale.ROOT);
            return upper.contains("KEY")
                    || upper.contains("TOKEN")
                    || upper.contains("SECRET")
                    || upper.contains("PASSWORD")
                    || upper.contains("PASSWD")
                    || upper.contains("CREDENTIAL")
                    || upper.startsWith("JASAGENT_");
        });
    }

    /**
     * 在超时和输出上限内读取进程已经合并的输出。进程结束、时间到或输出写满时返回已经读到的字节。
     *
     * @param process 已经启动的进程
     * @return 截断前读到的输出字节
     */
    private static byte[] readUntilExit(Process process) throws Exception {
        InputStream input = process.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        byte[] buffer = new byte[4096];
        while (System.nanoTime() < deadline && output.size() < MAX_OUTPUT) {
            while (input.available() > 0 && output.size() < MAX_OUTPUT) {
                int read = input.read(buffer, 0, Math.min(buffer.length, MAX_OUTPUT - output.size()));
                if (read < 0) {
                    return output.toByteArray();
                }
                if (read == 0) {
                    break;
                }
                output.write(buffer, 0, read);
            }
            if (!process.isAlive()) {
                int read;
                while (output.size() < MAX_OUTPUT && (read = input.read(buffer, 0, Math.min(buffer.length, MAX_OUTPUT - output.size()))) > 0) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
            Thread.sleep(30);
        }
        return output.toByteArray();
    }

    /**
     * 把输出字节按 UTF-8 收成文本。读满上限时在末尾加上截断说明。
     *
     * @param captured 已读到的输出
     * @return 给模型看的文本
     */
    private static String text(byte[] captured) {
        String value = new String(captured, StandardCharsets.UTF_8);
        if (captured.length >= MAX_OUTPUT) {
            return value + "\n...输出已截断";
        }
        return value;
    }
}
