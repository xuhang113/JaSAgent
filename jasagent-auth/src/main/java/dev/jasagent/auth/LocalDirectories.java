package dev.jasagent.auth;

import dev.jasagent.api.auth.DirectoryLayout;
import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

public final class LocalDirectories implements DirectoryLayout {
    private final Path homeRoot;

    public LocalDirectories(Path homeRoot) {
        this.homeRoot = homeRoot.toAbsolutePath().normalize();
    }

    public static LocalDirectories processHome() {
        return new LocalDirectories(Path.of(System.getProperty("user.home")));
    }

    @Override
    public Path homeRoot() {
        return homeRoot;
    }

    @Override
    public Path userDirectory(String login) {
        return homeRoot.resolve(LoginNames.require(login)).resolve("jasagent");
    }

    @Override
    public Path workspaceDirectory(Path workspace) {
        return workspace.resolve("jasagent");
    }

    @Override
    public CompletionStage<Path> checkWorkspace(Path candidate, List<Path> allowlist, Path currentWorkingDirectory) {
        return VirtualIo.supply(() -> checkSync(candidate, allowlist, currentWorkingDirectory));
    }

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

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
