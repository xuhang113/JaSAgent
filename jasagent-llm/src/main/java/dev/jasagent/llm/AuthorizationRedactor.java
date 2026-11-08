package dev.jasagent.llm;

import java.util.regex.Pattern;

public final class AuthorizationRedactor {
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)authorization\\s*[:=]\\s*bearer\\s+\\S+");
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile("(?i)authorization\\s*[:=]\\s*\\S+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+\\S+");

    private AuthorizationRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = AUTHORIZATION.matcher(text).replaceAll("");
        cleaned = AUTHORIZATION_VALUE.matcher(cleaned).replaceAll("");
        cleaned = BEARER.matcher(cleaned).replaceAll("");
        return cleaned;
    }
}
