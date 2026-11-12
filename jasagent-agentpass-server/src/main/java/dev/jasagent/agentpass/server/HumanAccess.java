package dev.jasagent.agentpass.server;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.auth.LocalDirectories;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 核对“这个登录的人能不能访问这次目标”。会话编号必须落在该用户自己的会话目录里，再问进程内 {@link PassGate}。
 * 只认 {@link PassDecision#ALLOW} 可以发一次性证。拒绝、等待和未启用不发证。
 * 这里使用策略门，不写 {@code auth/pending}，也不把执行标成挂起；那些仍由原来的进程内门负责。
 * 读取会话文件时只取 login 行，不把 token 行留在决定、流水或日志里。
 */
public final class HumanAccess {
    /**
     * 向进程内门申请时使用的参数标记。一次性证没有工具参数表，这个值只用来填满申请上的摘要字段。
     */
    public static final String POLICY_ARGUMENT_MARK = "one-time-pass";

    /**
     * 会话编号只允许作为单个文件名。拒绝斜线和跳转，避免读到用户目录外面的文件。
     */
    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$");

    private final LocalDirectories directories;
    private final PassGate passGate;

    /**
     * 绑定用户目录布局和进程内通行证门。门应当是按用户策略裁决的那一种，这样 ALLOW、DENY、PENDING 保持原义。
     *
     * @param directories 按登录名找到 {@code 家目录/登录名/jasagent} 的布局
     * @param passGate 按用户目录策略给出允许、拒绝或等待的门
     */
    public HumanAccess(LocalDirectories directories, PassGate passGate) {
        this.directories = directories;
        this.passGate = passGate;
    }

    /**
     * 判断这次领证请求能不能发证。会话编号只用来找到会话文件，登录令牌不会出现在返回的决定里。
     *
     * @param request 含租户、用户、会话编号、执行、目标和工具名的领证请求
     * @return 允许时带上会话编号；否则带上不发证的原因码
     */
    public Decision decide(IssuePassRequest request) {
        String userId = request.userId().trim();
        String sessionId = request.sessionId().trim();
        if (!SESSION_ID.matcher(sessionId).matches()) {
            return Decision.deny(PassReasons.NOT_LOGGED_IN);
        }
        try {
            Decision session = checkSession(userId, sessionId);
            if (session != null) {
                return session;
            }
        } catch (AgentException ex) {
            return Decision.deny(ex.code() == ErrorCode.AUTH ? PassReasons.NOT_LOGGED_IN : PassReasons.ACCESS_CHECK_FAILED);
        } catch (IOException ex) {
            return Decision.deny(PassReasons.ACCESS_CHECK_FAILED);
        }
        Pass pass;
        try {
            pass = await(passGate.acquire(new PassRequest(
                    userId,
                    request.executionId().trim(),
                    request.toolName().trim(),
                    null,
                    POLICY_ARGUMENT_MARK)));
        } catch (AgentException ex) {
            if (ex.code() == ErrorCode.AUTH) {
                return Decision.deny(PassReasons.NOT_LOGGED_IN);
            }
            return Decision.deny(ex.code() == ErrorCode.INTERNAL ? PassReasons.POLICY_UNREADABLE : PassReasons.ACCESS_CHECK_FAILED);
        } catch (RuntimeException ex) {
            return Decision.deny(PassReasons.ACCESS_CHECK_FAILED);
        }
        if (pass == null || pass.decision() == null) {
            return Decision.deny(PassReasons.ACCESS_CHECK_FAILED);
        }
        return switch (pass.decision()) {
            case ALLOW -> Decision.allow(sessionId);
            case DENY -> Decision.deny(PassReasons.POLICY_DENY);
            case PENDING -> Decision.deny(PassReasons.APPROVAL_REQUIRED);
            case SKIPPED -> Decision.deny(PassReasons.AGENTPASS_NOT_ENABLED);
        };
    }

    /**
     * 确认会话文件属于这个用户。找不到时，若编号落在别人的会话目录里，算会话不匹配。
     *
     * @param userId 声称的登录名
     * @param sessionId 会话编号
     * @return 不能发证时的决定；会话属于这个用户时为空，调用方继续问策略门
     */
    private Decision checkSession(String userId, String sessionId) throws IOException {
        Path own = directories.userDirectory(userId).resolve("auth").resolve("sessions").resolve(sessionId);
        if (Files.isRegularFile(own)) {
            if (!userId.equals(loginLine(own))) {
                return Decision.deny(PassReasons.SESSION_MISMATCH);
            }
            return null;
        }
        if (ownedBySomeoneElse(userId, sessionId)) {
            return Decision.deny(PassReasons.SESSION_MISMATCH);
        }
        return Decision.deny(PassReasons.NOT_LOGGED_IN);
    }

    /**
     * 只读取会话文件里的 login 行。token 行读过即丢，不返回、不记录。
     *
     * @param file 该用户会话目录下的会话文件
     * @return 文件里的登录名；没有 login 行时为空字符串
     */
    private static String loginLine(Path file) throws IOException {
        String login = "";
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("login=")) {
                login = line.substring("login=".length());
            }
        }
        return login;
    }

    /**
     * 看别的用户目录里有没有同名会话文件。只判断文件在不在，不打开内容。
     *
     * @param userId 这次声称的登录名，对应目录跳过
     * @param sessionId 会话编号
     * @return 另一个用户持有这个会话编号时为真
     */
    private boolean ownedBySomeoneElse(String userId, String sessionId) throws IOException {
        Path home = directories.homeRoot();
        if (!Files.isDirectory(home)) {
            return false;
        }
        try (Stream<Path> children = Files.list(home)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(".") || name.equals(userId)) {
                    continue;
                }
                Path file = child.resolve("jasagent").resolve("auth").resolve("sessions").resolve(sessionId);
                if (Files.isRegularFile(file)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 等异步阶段结束。阶段里包着的运行时异常按原异常抛出，便于上面区分认证失败和策略失败。
     *
     * @param stage 通行证门返回的阶段
     * @param <T> 阶段的结果类型
     * @return 阶段的结果
     */
    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw ex;
        }
    }

    /**
     * 人证核对的结果。允许时只带回会话编号，不带回登录令牌。
     *
     * @param allowed 可以签发一次性证时为真
     * @param sessionId 已登录会话的编号；拒绝时为空
     * @param reason 拒绝原因；允许时为空
     */
    public record Decision(boolean allowed, String sessionId, String reason) {
        /**
         * 构造一个允许发证的决定。
         *
         * @param sessionId 核对通过的会话编号
         * @return 允许决定
         */
        public static Decision allow(String sessionId) {
            return new Decision(true, sessionId, null);
        }

        /**
         * 构造一个不发证的决定。
         *
         * @param reason {@link PassReasons} 中的原因码
         * @return 拒绝决定
         */
        public static Decision deny(String reason) {
            return new Decision(false, null, reason);
        }
    }
}
