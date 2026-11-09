package dev.jasagent.auth;

import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.AuthService;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.auth.UserAccount;
import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * 用家目录里的口令文件和会话文件完成本地注册、登录、注销和工作区选择。
 */
public final class LocalAuthService implements AuthService {
    /**
     * 新建用户时必须先建好的相对目录，覆盖会话、规则、技能、订正和检索资料。
     */
    private static final List<String> USER_DIRECTORIES = List.of(
            "auth/sessions",
            "rules",
            "skills",
            "corrections",
            "rag/docs",
            "rag/index");

    private final LocalDirectories directories;

    /**
     * 用给定家目录根建立认证服务，目录布局由该根派生。
     *
     * @param homeRoot 各登录名目录的父目录
     */
    public LocalAuthService(Path homeRoot) {
        this(new LocalDirectories(homeRoot));
    }

    /**
     * 直接绑定已经确定的目录布局。
     *
     * @param directories 登录名、工作区和家目录根的布局
     */
    public LocalAuthService(LocalDirectories directories) {
        this.directories = directories;
    }

    /**
     * 把认证数据放在当前进程的用户家目录下。
     *
     * @return 使用进程家目录的认证服务
     */
    public static LocalAuthService processHome() {
        return new LocalAuthService(LocalDirectories.processHome());
    }

    /**
     * 暴露这份服务使用的目录布局，供目录守卫复用同一家目录根。
     *
     * @return 构造时绑定的目录布局
     */
    public LocalDirectories directories() {
        return directories;
    }

    /**
     * 在虚拟线程上创建用户、初始化其目录并写入口令摘要。首个用户可直接创建，之后必须带本机已登录会话。
     *
     * @param request 登录名、口令，以及是否由已登录本机命令发起
     * @return 完成后得到新建账号及其用户目录
     */
    @Override
    public CompletionStage<UserAccount> addUser(AddUser request) {
        return VirtualIo.supply(() -> addUserSync(request));
    }

    /**
     * 在虚拟线程上核对口令，通过后签发随机令牌并把会话写成仅属主可读的文件。
     *
     * @param login 登录名
     * @param password 本次提交的口令字符
     * @return 完成后得到尚未选择工作区的新会话
     */
    @Override
    public CompletionStage<Session> login(String login, char[] password) {
        return VirtualIo.supply(() -> loginSync(login, password));
    }

    /**
     * 按令牌找到会话文件并删除，使该令牌不能再通过认证。
     *
     * @param token 要作废的会话令牌
     * @return 会话文件删除后完成的阶段
     */
    @Override
    public CompletionStage<Void> logout(String token) {
        return VirtualIo.supply(() -> {
            StoredSession stored = findStored(token);
            Files.deleteIfExists(stored.file());
            return null;
        });
    }

    /**
     * 按令牌找回仍落在磁盘上的会话。
     *
     * @param token 调用方持有的会话令牌
     * @return 完成后得到该令牌对应的会话；找不到时阶段失败
     */
    @Override
    public CompletionStage<Session> authenticate(String token) {
        return VirtualIo.supply(() -> findStored(token).session());
    }

    /**
     * 为已有会话选定工作区，并把带工作区的会话写回原会话文件。
     *
     * @param token 已登录会话的令牌
     * @param workspace 要绑定的工作区
     * @param allowlist 允许的工作区；空列表时只能选当前目录
     * @param currentWorkingDirectory 用来判断候选路径的当前目录
     * @return 完成后得到已写入工作区的会话
     */
    @Override
    public CompletionStage<Session> selectWorkspace(String token, Path workspace, List<Path> allowlist, Path currentWorkingDirectory) {
        return VirtualIo.supply(() -> {
            StoredSession stored = findStored(token);
            Path selected = directories.checkSync(workspace, allowlist, currentWorkingDirectory);
            Session updated = new Session(
                    stored.session().sessionId(),
                    stored.session().token(),
                    stored.session().login(),
                    selected.toString(),
                    stored.session().createdAt());
            OwnerFiles.write(stored.file(), encode(updated));
            return updated;
        });
    }

    /**
     * 同步创建用户目录和口令文件。已有任何用户时，必须是携带有效令牌的本机命令。
     * 副作用是创建用户子目录、收紧 auth 目录权限，并写入口令摘要。
     *
     * @param request 建账请求
     * @return 新建账号及其 jasagent 目录
     */
    private UserAccount addUserSync(AddUser request) throws IOException {
        String login = LoginNames.require(request.login());
        if (request.password() == null || request.password().length == 0) {
            throw new AgentException(ErrorCode.AUTH, "口令不能为空");
        }
        if (anyUserExists()) {
            if (!request.localCli()) {
                throw new AgentException(ErrorCode.AUTH, "只能由已登录的本机命令创建用户");
            }
            findStored(request.callerToken());
        }
        Path directory = directories.userDirectory(login);
        Path hashFile = directory.resolve("auth").resolve("password.hash");
        if (Files.exists(hashFile)) {
            throw new AgentException(ErrorCode.AUTH, "用户已存在");
        }
        for (String relative : USER_DIRECTORIES) {
            Files.createDirectories(directory.resolve(relative));
        }
        Files.setPosixFilePermissions(directory.resolve("auth"), OwnerFiles.DIR_700);
        Files.setPosixFilePermissions(directory.resolve("auth").resolve("sessions"), OwnerFiles.DIR_700);
        OwnerFiles.write(hashFile, PasswordHashes.hash(request.password()));
        return new UserAccount(login, directory);
    }

    /**
     * 同步核对口令摘要，生成会话标识和 32 字节令牌，并把会话正文写入该用户的 sessions 目录。
     *
     * @param login 登录名
     * @param password 待核对的口令字符
     * @return 新建立、工作区仍为空的会话
     */
    private Session loginSync(String login, char[] password) throws IOException {
        String validLogin = LoginNames.require(login);
        Path hashFile = directories.userDirectory(validLogin).resolve("auth").resolve("password.hash");
        if (!Files.isRegularFile(hashFile) || password == null || !PasswordHashes.verify(password, Files.readString(hashFile))) {
            throw new AgentException(ErrorCode.AUTH, "登录失败");
        }
        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        Session session = new Session(
                UUID.randomUUID().toString(),
                HexFormat.of().formatHex(tokenBytes),
                validLogin,
                null,
                Instant.now());
        Path file = sessionFile(validLogin, session.sessionId());
        OwnerFiles.write(file, encode(session));
        return session;
    }

    /**
     * 扫描家目录，看是否已经有人写下口令摘要，用来区分首个用户和后续建账。
     *
     * @return 任一非隐藏登录名下已有 password.hash 时为真
     */
    private boolean anyUserExists() throws IOException {
        Path home = directories.homeRoot();
        if (!Files.isDirectory(home)) {
            return false;
        }
        try (Stream<Path> children = Files.list(home)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (Files.isRegularFile(child.resolve("jasagent").resolve("auth").resolve("password.hash"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 在所有用户的 sessions 目录里按令牌查找会话文件。
     *
     * @param token 待查找的令牌；空白令牌直接视为无效
     * @return 命中的会话文件和解析出的会话
     */
    private StoredSession findStored(String token) throws IOException {
        if (token == null || token.isBlank()) {
            throw new AgentException(ErrorCode.AUTH, "会话无效");
        }
        Path home = directories.homeRoot();
        if (!Files.isDirectory(home)) {
            throw new AgentException(ErrorCode.AUTH, "会话无效");
        }
        try (Stream<Path> children = Files.list(home)) {
            for (Path child : children.toList()) {
                if (child.getFileName().toString().startsWith(".")) {
                    continue;
                }
                Path sessions = child.resolve("jasagent").resolve("auth").resolve("sessions");
                if (!Files.isDirectory(sessions)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(sessions)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        Session session = decode(file);
                        if (session != null && token.equals(session.token())) {
                            return new StoredSession(file, session);
                        }
                    }
                }
            }
        }
        throw new AgentException(ErrorCode.AUTH, "会话无效");
    }

    /**
     * 计算某个登录名下指定会话标识的落盘路径。
     *
     * @param login 会话所属登录名
     * @param sessionId 会话标识，也是文件名
     * @return 该会话文件的路径
     */
    private Path sessionFile(String login, String sessionId) {
        return directories.userDirectory(login).resolve("auth").resolve("sessions").resolve(sessionId);
    }

    /**
     * 把会话收成令牌、登录名、工作区和创建时间各占一行的文本。
     *
     * @param session 要落盘的会话
     * @return 供会话文件保存的正文
     */
    private static String encode(Session session) {
        return "token=" + session.token()
                + "\nlogin=" + session.login()
                + "\nworkspace=" + (session.workspace() == null ? "" : session.workspace())
                + "\ncreatedAt=" + session.createdAt()
                + "\n";
    }

    /**
     * 从会话文件还原会话；缺少令牌、登录名或创建时间时视为损坏文件。
     *
     * @param file 会话文件，文件名即会话标识
     * @return 解析出的会话；关键字段缺失时为空
     */
    private static Session decode(Path file) throws IOException {
        String token = "";
        String login = "";
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
                case "login" -> login = value;
                case "workspace" -> workspace = value;
                case "createdAt" -> createdAt = value;
                default -> {
                }
            }
        }
        if (token.isBlank() || login.isBlank() || createdAt.isBlank()) {
            return null;
        }
        return new Session(file.getFileName().toString(), token, login, workspace.isBlank() ? null : workspace, Instant.parse(createdAt));
    }

    /**
     * 一次按令牌命中的结果，同时留下文件位置和已解析会话，便于注销或改写工作区。
     *
     * @param file 会话正文所在文件
     * @param session 从该文件解析出的会话
     */
    private record StoredSession(Path file, Session session) {
    }
}
