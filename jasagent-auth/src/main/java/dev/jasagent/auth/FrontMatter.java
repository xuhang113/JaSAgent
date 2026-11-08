package dev.jasagent.auth;

import java.util.LinkedHashMap;
import java.util.Map;

final class FrontMatter {
    private final Map<String, String> meta;
    private final String body;

    private FrontMatter(Map<String, String> meta, String body) {
        this.meta = meta;
        this.body = body;
    }

    static FrontMatter parse(String text) {
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return new FrontMatter(Map.of(), normalized.strip());
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            return new FrontMatter(Map.of(), normalized.strip());
        }
        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : normalized.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            meta.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        return new FrontMatter(Map.copyOf(meta), normalized.substring(end + 5).strip());
    }

    Map<String, String> meta() {
        return meta;
    }

    String body() {
        return body;
    }
}
