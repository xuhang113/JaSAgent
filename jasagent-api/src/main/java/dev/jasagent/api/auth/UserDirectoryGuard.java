package dev.jasagent.api.auth;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

public interface UserDirectoryGuard {
    CompletionStage<Path> resolve(String requesterLogin, String ownerLogin, Path relative);
}
