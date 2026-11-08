package dev.jasagent.auth;

import dev.jasagent.api.auth.UserDirectoryGuard;
import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

public final class LocalUserDirectoryGuard implements UserDirectoryGuard {
    private final LocalDirectories directories;

    public LocalUserDirectoryGuard(LocalDirectories directories) {
        this.directories = directories;
    }

    @Override
    public CompletionStage<Path> resolve(String requesterLogin, String ownerLogin, Path relative) {
        return VirtualIo.supply(() -> resolveSync(requesterLogin, ownerLogin, relative));
    }

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
