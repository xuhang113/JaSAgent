package dev.jasagent.api.text;

import java.util.regex.Pattern;

public final class SecretRedactor {
    private static final Pattern BEARER = Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)\\S+");
    private static final Pattern JSON = Pattern.compile("(?i)(\"authorization\"\\s*:\\s*\")[^\"]*\"");

    private SecretRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String withoutBearer = BEARER.matcher(text).replaceAll("$1[redacted]");
        return JSON.matcher(withoutBearer).replaceAll("$1[redacted]\"");
    }
}
