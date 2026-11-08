package dev.jasagent.api.auth;

import java.nio.file.Path;

public record UserAccount(String login, Path directory) {
}
