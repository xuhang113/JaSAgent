package dev.jasagent.auth;

import dev.jasagent.api.auth.UserDirectoryGuard;
import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/**
 * 把相对路径限制在请求者自己的用户目录内，拒绝跨用户访问和跳出目录根的解析。
 */
public final class LocalUserDirectoryGuard implements UserDirectoryGuard {
    private final LocalDirectories directories;

    /**
     * 记住本机用户目录布局，之后的解析都相对这份布局计算。
     *
     * @param directories 登录名到用户 jasagent 目录的布局
     */
    public LocalUserDirectoryGuard(LocalDirectories directories) {
        this.directories = directories;
    }

    /**
     * 在虚拟线程上核对请求者就是目录属主，并把相对路径收敛到该用户的 jasagent 目录之内。
     *
     * @param requesterLogin 当前请求者的登录名
     * @param ownerLogin 被访问目录的属主登录名，必须与请求者相同
     * @param relative 相对该用户目录的路径；空值或绝对路径会被拒绝
     * @return 完成后得到规范化的绝对路径
     */
    @Override
    public CompletionStage<Path> resolve(String requesterLogin, String ownerLogin, Path relative) {
        return VirtualIo.supply(() -> resolveSync(requesterLogin, ownerLogin, relative));
    }

    /**
     * 同步完成登录名校验、属主核对和路径收敛，越界时抛出认证错误。
     *
     * @param requesterLogin 当前请求者的登录名
     * @param ownerLogin 被访问目录的属主登录名
     * @param relative 相对用户目录的路径
     * @return 落在该用户 jasagent 目录之内的绝对路径
     */
    private Path resolveSync(String requesterLogin, String ownerLogin, Path relative) {
        String requester = LoginNames.require(requesterLogin);
        String owner = LoginNames.require(ownerLogin);
        if (!requester.equals(owner)) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的目录");
        }
        if (relative == null || relative.isAbsolute()) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的目录");
        }
        Path base = directories.userDirectory(owner).toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的目录");
        }
        return resolved;
    }
}
