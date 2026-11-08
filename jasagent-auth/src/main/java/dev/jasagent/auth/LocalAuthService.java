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

public final class LocalAuthService implements AuthService {
    private static final List<String> USER_DIRECTORIES = List.of(
            "auth/sessions",
            "rules",
            "skills",
            "corrections",
            "rag/docs",
            "rag/index");

    private final LocalDirectories directories;

    public LocalAuthService(Path homeRoot) {
        this(new LocalDirectories(homeRoot));
    }

    public LocalAuthService(LocalDirectories directories) {
        this.directories = directories;
    }

    public static LocalAuthService processHome() {
        return new LocalAuthService(LocalDirectories.processHome());
    }

    public LocalDirectories directories() {
        return directories;
    }

    @Override
    public CompletionStage<UserAccount> addUser(AddUser request) {
        return VirtualIo.supply(() -> addUserSync(request));
    }

    @Override
    public CompletionStage<Session> login(String login, char[] password) {
        return VirtualIo.supply(() -> loginSync(login, password));
    }

    @Override
    public CompletionStage<Void> logout(String token) {
        return VirtualIo.supply(() -> {
            StoredSession stored = findStored(token);
            Files.deleteIfExists(stored.file());
            return null;
        });
    }

    @Override
    public CompletionStage<Session> authenticate(String token) {
        return VirtualIo.supply(() -> findStored(token).session());
    }

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

    private Path sessionFile(String login, String sessionId) {
        return directories.userDirectory(login).resolve("auth").resolve("sessions").resolve(sessionId);
    }

    private static String encode(Session session) {
        return "token=" + session.token()
                + "\nlogin=" + session.login()
                + "\nworkspace=" + (session.workspace() == null ? "" : session.workspace())
                + "\ncreatedAt=" + session.createdAt()
                + "\n";
    }

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

    private record StoredSession(Path file, Session session) {
    }
}
