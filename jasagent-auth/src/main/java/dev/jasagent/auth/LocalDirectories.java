package dev.jasagent.auth;

import dev.jasagent.api.auth.DirectoryLayout;
import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 按登录名把本机家目录分成用户空间，并把工作区选择限制在当前目录或允许列表中。
 */
public final class LocalDirectories implements DirectoryLayout {
    private final Path homeRoot;

    /**
     * 固定这套布局使用的家目录根，并先规范成绝对路径。
     *
     * @param homeRoot 各登录名用户目录的父目录
     */
    public LocalDirectories(Path homeRoot) {
        this.homeRoot = homeRoot.toAbsolutePath().normalize();
    }

    /**
     * 用当前进程的 {@code user.home} 作为家目录根。
     *
     * @return 绑定进程家目录的布局
     */
    public static LocalDirectories processHome() {
        return new LocalDirectories(Path.of(System.getProperty("user.home")));
    }

    /**
     * 返回构造时规范化后的家目录根，供会话扫描和用户目录拼接使用。
     *
     * @return 家目录根的绝对路径
     */
    @Override
    public Path homeRoot() {
        return homeRoot;
    }

    /**
     * 把登录名映射到该用户专属的 jasagent 目录。
     *
     * @param login 已通过字符规则、或将在此处再校验的登录名
     * @return {@code 家目录/登录名/jasagent}
     */
    @Override
    public Path userDirectory(String login) {
        return homeRoot.resolve(LoginNames.require(login)).resolve("jasagent");
    }

    /**
     * 定位某个工作区里存放规则和技能的 jasagent 目录。
     *
     * @param workspace 工作区根目录
     * @return {@code workspace/jasagent}
     */
    @Override
    public Path workspaceDirectory(Path workspace) {
        return workspace.resolve("jasagent");
    }

    /**
     * 在虚拟线程上判断候选工作区是否就是当前目录，或是否精确命中允许列表。
     *
     * @param candidate 用户选择的工作区，可以是相对当前目录的路径
     * @param allowlist 允许的绝对或可规范化路径；空列表表示只接受当前目录
     * @param currentWorkingDirectory 发起选择时的当前工作目录
     * @return 完成后得到被接受的绝对路径
     */
    @Override
    public CompletionStage<Path> checkWorkspace(Path candidate, List<Path> allowlist, Path currentWorkingDirectory) {
        return VirtualIo.supply(() -> checkSync(candidate, allowlist, currentWorkingDirectory));
    }

    /**
     * 在虚拟线程上确认请求者就是属主，并把相对路径收敛到该用户目录内。
     *
     * @param requesterLogin 当前请求者的登录名
     * @param ownerLogin 目录属主登录名，必须与请求者相同
     * @param relative 相对用户目录的路径
     * @return 完成后得到用户目录内的绝对路径
     */
    @Override
    public CompletionStage<Path> resolveOwn(String requesterLogin, String ownerLogin, Path relative) {
        return VirtualIo.supply(() -> resolveOwnSync(requesterLogin, ownerLogin, relative));
    }

    /**
     * 同步拒绝跨用户、绝对路径和逃出用户目录的相对路径。
     *
     * @param requesterLogin 当前请求者的登录名
     * @param ownerLogin 目录属主登录名
     * @param relative 相对用户目录的路径
     * @return 落在该用户 jasagent 目录之内的绝对路径
     */
    private Path resolveOwnSync(String requesterLogin, String ownerLogin, Path relative) {
        String requester = LoginNames.require(requesterLogin);
        String owner = LoginNames.require(ownerLogin);
        if (!requester.equals(owner)) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的目录");
        }
        if (relative == null || relative.isAbsolute()) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的目录");
        }
        Path base = userDirectory(owner).toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new AgentException(ErrorCode.AUTH, "不能读取其他用户的目录");
        }
        return resolved;
    }

    /**
     * 同步检查工作区：允许列表为空时只接受当前目录，否则候选必须与列表中某一项规范化后完全相同。
     *
     * @param candidate 待检查的工作区
     * @param allowlist 允许的工作区；空或空列表时只接受当前目录
     * @param currentWorkingDirectory 用来解析相对候选路径的当前目录
     * @return 被接受的绝对规范化路径
     */
    Path checkSync(Path candidate, List<Path> allowlist, Path currentWorkingDirectory) {
        if (candidate == null || currentWorkingDirectory == null) {
            throw new AgentException(ErrorCode.AUTH, "工作区无效");
        }
        Path cwd = absolute(currentWorkingDirectory);
        Path chosen = absolute(candidate.isAbsolute() ? candidate : cwd.resolve(candidate));
        if (allowlist == null || allowlist.isEmpty()) {
            if (!chosen.equals(cwd)) {
                throw new AgentException(ErrorCode.AUTH, "只允许当前工作目录");
            }
            return chosen;
        }
        for (Path allowed : allowlist) {
            if (allowed != null && chosen.equals(absolute(allowed))) {
                return chosen;
            }
        }
        throw new AgentException(ErrorCode.AUTH, "工作区不在允许列表中");
    }

    /**
     * 把路径转成绝对路径并去掉 {@code .} 和 {@code ..}。
     *
     * @param path 原始路径
     * @return 绝对规范化路径
     */
    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
