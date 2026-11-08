package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Chunker {
    static final int WINDOW = 800;
    static final int OVERLAP = 100;
    static final int STEP = WINDOW - OVERLAP;
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})[ \\t]+(.+?)[ \\t]*$");

    private Chunker() {
    }

    public static List<TextChunk> chunk(String uri, String text) {
        String body = text == null ? "" : text;
        List<Section> sections = sections(stem(uri), body);
        List<TextChunk> chunks = new ArrayList<>();
        int number = 0;
        for (Section section : sections) {
            int length = section.end() - section.start();
            if (length <= 0) {
                continue;
            }
            if (length <= WINDOW) {
                chunks.add(piece(uri, number, section.title(), section.start(), section.end(), body));
                number++;
                continue;
            }
            for (int offset = section.start(); offset < section.end(); offset += STEP) {
                int end = Math.min(offset + WINDOW, section.end());
                chunks.add(piece(uri, number, section.title(), offset, end, body));
                number++;
                if (end == section.end()) {
                    break;
                }
            }
        }
        return chunks;
    }

    private static TextChunk piece(String uri, int number, String title, int start, int end, String text) {
        return new TextChunk(uri, title, uri + "#" + number, start, end, text.substring(start, end));
    }

    private static List<Section> sections(String fallbackTitle, String text) {
        Matcher matcher = HEADING.matcher(text);
        List<Section> sections = new ArrayList<>();
        int cursor = 0;
        String title = fallbackTitle;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                sections.add(new Section(title, cursor, matcher.start()));
            }
            title = matcher.group(2).trim();
            cursor = matcher.start();
        }
        if (cursor < text.length() || sections.isEmpty()) {
            sections.add(new Section(title, cursor, text.length()));
        }
        return sections;
    }

    static String stem(String uri) {
        String name = uri == null ? "" : uri;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name;
    }

    private record Section(String title, int start, int end) {
    }
}
