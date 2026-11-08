package dev.jasagent.api.auth;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface DirectoryLayout {
    Path homeRoot();

    Path userDirectory(String login);

    Path workspaceDirectory(Path workspace);

    CompletionStage<Path> checkWorkspace(Path candidate, List<Path> allowlist, Path currentWorkingDirectory);
}
