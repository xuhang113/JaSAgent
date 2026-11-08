package dev.jasagent.api.auth;

import java.time.Instant;

public record Session(String sessionId, String token, String login, String workspace, Instant createdAt) {
}
