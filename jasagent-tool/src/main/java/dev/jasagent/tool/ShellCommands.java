package dev.jasagent.tool;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class ShellCommands {
    private static final int TIMEOUT_SECONDS = 20;
    private static final int MAX_OUTPUT = 12_000;
    private static final Set<String> COMMANDS = Set.of(
            "ls", "find", "wc", "pwd", "echo", "stat", "file", "head", "tail",
            "rg", "grep", "egrep", "fgrep", "git", "cat", "cut", "sort", "uniq",
            "tr", "basename", "dirname", "realpath", "du", "df", "date", "uname",
            "which", "printf", "true", "false", "id", "whoami", "sed", "awk",
            "nl", "diff", "cmp", "comm", "column");
    private static final Set<String> GIT = Set.of(
            "status", "log", "diff", "show", "rev-parse", "ls-files", "blame", "shortlog", "describe");

    private ShellCommands() {
    }

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

    private static boolean catastrophic(String command) {
        String folded = command.toLowerCase(Locale.ROOT);
        return folded.contains("mkfs")
                || folded.contains(":(){")
                || folded.contains("rm -rf /")
                || folded.contains("rm -fr /");
    }

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

    private static String text(byte[] captured) {
        String value = new String(captured, StandardCharsets.UTF_8);
        if (captured.length >= MAX_OUTPUT) {
            return value + "\n...输出已截断";
        }
        return value;
    }
}
