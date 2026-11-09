package dev.jasagent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ToolJson {
    private ToolJson() {
    }

    static Map<String, Object> parse(String raw) {
        if (raw == null) {
            return Map.of();
        }
        String text = raw.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return Map.of();
        }
        try {
            Cursor cursor = new Cursor(text);
            Map<String, Object> value = cursor.object();
            cursor.skip();
            if (!cursor.done()) {
                return Map.of();
            }
            return value;
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    static int number(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            List<String> rows = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    rows.add(String.valueOf(item).trim());
                }
            }
            return rows;
        }
        String text = text(values, key);
        if (text.isBlank()) {
            return List.of();
        }
        List<String> rows = new ArrayList<>();
        for (String part : text.split("[,\\n]")) {
            if (!part.isBlank()) {
                rows.add(part.trim());
            }
        }
        return rows;
    }

    private static final class Cursor {
        private final String text;
        private int index;

        private Cursor(String text) {
            this.text = text;
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> values = new LinkedHashMap<>();
            skip();
            if (peek('}')) {
                index++;
                return values;
            }
            while (index < text.length()) {
                skip();
                String key = string();
                skip();
                expect(':');
                skip();
                values.put(key, value());
                skip();
                if (peek('}')) {
                    index++;
                    return values;
                }
                expect(',');
            }
            throw new IllegalArgumentException("json");
        }

        private Object value() {
            skip();
            if (index >= text.length()) {
                throw new IllegalArgumentException("json");
            }
            char current = text.charAt(index);
            if (current == '"') {
                return string();
            }
            if (current == '{') {
                return object();
            }
            if (current == '[') {
                return array();
            }
            if (text.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", index)) {
                index += 4;
                return "";
            }
            int start = index;
            if (current == '-') {
                index++;
            }
            while (index < text.length() && (Character.isDigit(text.charAt(index)) || text.charAt(index) == '.')) {
                index++;
            }
            if (start == index || (text.charAt(start) == '-' && start + 1 == index)) {
                throw new IllegalArgumentException("json");
            }
            String number = text.substring(start, index);
            if (number.contains(".")) {
                return Double.parseDouble(number);
            }
            return Integer.parseInt(number);
        }

        private List<Object> array() {
            expect('[');
            List<Object> values = new ArrayList<>();
            skip();
            if (peek(']')) {
                index++;
                return values;
            }
            while (index < text.length()) {
                values.add(value());
                skip();
                if (peek(']')) {
                    index++;
                    return values;
                }
                expect(',');
                skip();
            }
            throw new IllegalArgumentException("json");
        }

        private String string() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    return value.toString();
                }
                if (current != '\\') {
                    value.append(current);
                    continue;
                }
                if (index >= text.length()) {
                    throw new IllegalArgumentException("json");
                }
                char escaped = text.charAt(index++);
                value.append(switch (escaped) {
                    case '"', '\\', '/' -> escaped;
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> unicode();
                    default -> throw new IllegalArgumentException("json");
                });
            }
            throw new IllegalArgumentException("json");
        }

        private char unicode() {
            if (index + 4 > text.length()) {
                throw new IllegalArgumentException("json");
            }
            int code = Integer.parseInt(text.substring(index, index + 4), 16);
            index += 4;
            return (char) code;
        }

        private void expect(char expected) {
            skip();
            if (index >= text.length() || text.charAt(index) != expected) {
                throw new IllegalArgumentException("json");
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        private void skip() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean done() {
            return index >= text.length();
        }
    }
}
