package dev.jasagent.api.auth;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

public interface DirectoryLayout {
    Path userDirectory(String login);

    Path workspaceDirectory(Path workspace);

    CompletionStage<Path> resolveOwn(String requesterLogin, String ownerLogin, Path relative);
}
