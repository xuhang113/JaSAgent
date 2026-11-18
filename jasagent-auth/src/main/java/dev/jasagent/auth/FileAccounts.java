package dev.jasagent.auth;

import dev.jasagent.api.exception.AgentException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 从家目录里读出已经落盘的运维账号，供首次迁入 MySQL。
 * 只读取口令摘要和会话文件，不把摘要或令牌写进日志。读完后账号的权威存储是 MySQL。
 */
final class FileAccounts {
    /**
     * 摘要最长接受的字符数。更长的文件不当成口令摘要。
     */
    private static final int MAX_HASH_CHARS = 512;

    /**
     * 禁止实例化。读取只通过 {@link #read(Path)}。
     */
    private FileAccounts() {
    }

    /**
     * 扫描家目录下每个登录名的 password.hash 和会话文件。隐藏目录和登录名不合法的目录跳过。
     *
     * @param home 各登录名目录的父目录；不存在时得到空列表
     * @return 可以迁入的账号；没有时是空列表
     * @throws IOException 列目录失败时抛出，调用方改写成不含口令的说明
     */
    static List<User> read(Path home) throws IOException {
        if (home == null || !Files.isDirectory(home)) {
            return List.of();
        }
        List<User> found = new ArrayList<>();
        try (var children = Files.list(home)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                try {
                    LoginNames.require(name);
                } catch (AgentException ex) {
                    continue;
                }
                User user = readUser(child, name);
                if (user != null) {
                    found.add(user);
                }
            }
        }
        return found;
    }

    /**
     * 读取一个登录名目录里的摘要和会话。摘要不是 PBKDF2 时不当成账号。
     *
     * @param child 登录名目录
     * @param login 已经通过校验的登录名
     * @return 账号；没有合格摘要时为空
     * @throws IOException 读文件失败时抛出
     */
    private static User readUser(Path child, String login) throws IOException {
        Path hashFile = child.resolve("jasagent").resolve("auth").resolve("password.hash");
        if (!Files.isRegularFile(hashFile)) {
            return null;
        }
        String hash = Files.readString(hashFile, StandardCharsets.UTF_8).trim();
        if (hash.length() > MAX_HASH_CHARS || !hash.startsWith("pbkdf2-sha256$") || hash.chars().filter(ch -> ch == '$').count() != 3) {
            return null;
        }
        return new User(login, hash, readSessions(child.resolve("jasagent").resolve("auth").resolve("sessions"), login));
    }

    /**
     * 读取会话目录。损坏的文件跳过，不让一份坏会话挡住整个账号迁入。
     *
     * @param sessions 会话目录
     * @param login 这些会话应属的登录名
     * @return 能解析的会话；目录不存在时是空列表
     * @throws IOException 列目录失败时抛出
     */
    private static List<SessionFile> readSessions(Path sessions, String login) throws IOException {
        if (!Files.isDirectory(sessions)) {
            return List.of();
        }
        List<SessionFile> found = new ArrayList<>();
        try (var files = Files.list(sessions)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                SessionFile parsed = decode(file, login);
                if (parsed != null) {
                    found.add(parsed);
                }
            }
        }
        return found;
    }

    /**
     * 按文件里的 token、login、workspace、createdAt 还原会话。令牌或登录名对不上时丢掉。
     *
     * @param file 会话文件，文件名是会话编号
     * @param login 目录所属登录名
     * @return 会话；关键字段缺失时为空
     * @throws IOException 读文件失败时抛出
     */
    private static SessionFile decode(Path file, String login) throws IOException {
        String token = "";
        String storedLogin = "";
        String workspace = "";
        String createdAt = "";
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            int split = line.indexOf('=');
            if (split <= 0) {
                continue;
            }
            String key = line.substring(0, split);
            String value = line.substring(split + 1);
            switch (key) {
                case "token" -> token = value;
                case "login" -> storedLogin = value;
                case "workspace" -> workspace = value;
                case "createdAt" -> createdAt = value;
                default -> {
                }
            }
        }
        if (token.isBlank() || !login.equals(storedLogin) || createdAt.isBlank()) {
            return null;
        }
        String sessionId = file.getFileName().toString();
        if (sessionId.isBlank() || sessionId.length() > 64) {
            return null;
        }
        try {
            return new SessionFile(sessionId, token, workspace.isBlank() ? null : workspace, Instant.parse(createdAt));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 一份待迁入的运维账号。摘要是 PBKDF2 串，不是口令原文。
     *
     * @param login 登录名
     * @param passwordHash 口令摘要
     * @param sessions 仍在文件里的登录会话
     */
    record User(String login, String passwordHash, List<SessionFile> sessions) {
        /**
         * 文本里不放摘要，避免被日志打出来。
         *
         * @return 只含登录名
         */
        @Override
        public String toString() {
            return "FileAccounts.User[" + login + "]";
        }
    }

    /**
     * 一份待迁入的登录会话。令牌只在迁入时哈希，不进入 {@link #toString()}。
     *
     * @param sessionId 会话编号
     * @param token 文件里的令牌
     * @param workspace 已选工作区；没有时为空
     * @param createdAt 创建时间
     */
    record SessionFile(String sessionId, String token, String workspace, Instant createdAt) {
        /**
         * 文本里不放令牌。
         *
         * @return 只含会话编号
         */
        @Override
        public String toString() {
            return "FileAccounts.SessionFile[" + sessionId + "]";
        }
    }
}
