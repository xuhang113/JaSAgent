package dev.jasagent.api.auth;

public record AddUser(String login, char[] password, String callerToken, boolean localCli) {
}
