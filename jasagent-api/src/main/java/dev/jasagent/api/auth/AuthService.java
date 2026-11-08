package dev.jasagent.api.auth;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface AuthService {
    CompletionStage<UserAccount> addUser(AddUser request);

    CompletionStage<Session> login(String login, char[] password);

    CompletionStage<Void> logout(String token);

    CompletionStage<Session> authenticate(String token);

    CompletionStage<Session> selectWorkspace(String token, Path workspace, List<Path> allowlist, Path currentWorkingDirectory);
}
