package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Chunker {
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6} +(.+)$");
    static final int WINDOW = 800;
    static final int OVERLAP = 100;

    private Chunker() {
    }

    public static List<Chunk> split(String text) {
        List<Chunk> chunks = new ArrayList<>();
        Matcher matcher = HEADING.matcher(text);
        List<Integer> starts = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
            titles.add(matcher.group(1).trim());
        }
        if (starts.isEmpty()) {
            chunks.addAll(windows("doc", "body", text, 0));
            return chunks;
        }
        for (int index = 0; index < starts.size(); index++) {
            int bodyStart = text.indexOf('\n', starts.get(index));
            int bodyEnd = index + 1 < starts.size() ? starts.get(index + 1) : text.length();
            String body = bodyStart < 0 || bodyStart >= bodyEnd ? "" : text.substring(bodyStart + 1, bodyEnd);
            chunks.add(new Chunk("doc", "title-" + index, titles.get(index), starts.get(index), bodyStart, "user", "memory"));
            chunks.addAll(windows("doc", "sec-" + index, body, Math.max(bodyStart + 1, starts.get(index))));
        }
        return chunks;
    }

    private static List<Chunk> windows(String docId, String prefix, String body, int base) {
        List<Chunk> chunks = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return chunks;
        }
        int offset = 0;
        int part = 0;
        while (offset < body.length()) {
            int end = Math.min(body.length(), offset + WINDOW);
            String slice = body.substring(offset, end);
            chunks.add(new Chunk(docId, prefix + "-" + part, slice, base + offset, base + end, "user", "memory"));
            if (end == body.length()) {
                break;
            }
            offset += WINDOW - OVERLAP;
            part++;
        }
        return chunks;
    }
}
