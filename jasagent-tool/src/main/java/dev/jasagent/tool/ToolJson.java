package dev.jasagent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析工具参数里的一小段 JSON，并按键取出文本、整数和字符串列表。
 */
final class ToolJson {
    /**
     * 禁止实例化。解析只通过静态方法完成。
     */
    private ToolJson() {
    }

    /**
     * 把一个 JSON 对象收成保持原顺序的映射。null、非对象、尾部还有内容或语法不对时返回空映射。
     *
     * @param raw 工具参数原文
     * @return 解析出的键值；无法当成一个完整对象时返回空映射
     */
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

    /**
     * 取出某个键的文本并去掉两端空白。
     *
     * @param values 已解析的参数
     * @param key 参数名
     * @return 该键的文本；缺失时返回空串
     */
    static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 取出某个键的整数。数字或整数字符串都认，缺失或格式不对时用兜底值。
     *
     * @param values 已解析的参数
     * @param key 参数名
     * @param fallback 缺失或不是整数时返回的值
     * @return 解析出的整数或兜底值
     */
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

    /**
     * 取出某个键的字符串列表。值本身是列表时逐项去空白；否则按逗号或换行拆开一段文本。
     *
     * @param values 已解析的参数
     * @param key 参数名
     * @return 去掉空白项后的字符串；没有内容时返回空列表
     */
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

    /**
     * 在一段 JSON 文本上向前读取，只处理工具参数里会出现的对象、数组、字符串和数字。
     */
    private static final class Cursor {
        private final String text;
        private int index;

        /**
         * 从这段文本的开头开始读。
         *
         * @param text 待解析的 JSON 文本
         */
        private Cursor(String text) {
            this.text = text;
        }

        /**
         * 读取一个 JSON 对象，键按出现顺序放入映射。
         *
         * @return 对象里的键和值
         */
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

        /**
         * 读取当前位置的一个值。布尔保留原值，null 收成空串，带小数点的数收成浮点，其余数字收成整数。
         *
         * @return 读到的值
         */
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

        /**
         * 读取一个 JSON 数组。
         *
         * @return 按顺序排列的元素
         */
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

        /**
         * 读取一个带转义的 JSON 字符串。
         *
         * @return 反转义后的文本
         */
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

        /**
         * 读取反斜杠 u 后面的四位十六进制，并收成一个字符。
         *
         * @return 对应的字符
         */
        private char unicode() {
            if (index + 4 > text.length()) {
                throw new IllegalArgumentException("json");
            }
            int code = Integer.parseInt(text.substring(index, index + 4), 16);
            index += 4;
            return (char) code;
        }

        /**
         * 跳过空白后要求下一个字符就是给定字符，并越过它。字符不符时抛出格式错误。
         *
         * @param expected 当前位置必须出现的字符
         */
        private void expect(char expected) {
            skip();
            if (index >= text.length() || text.charAt(index) != expected) {
                throw new IllegalArgumentException("json");
            }
            index++;
        }

        /**
         * 看当前位置是不是给定字符，不移动读取位置。
         *
         * @param expected 要对照的字符
         * @return 当前位置正好是该字符时为 true
         */
        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        /**
         * 越过当前位置起连续的空白。
         */
        private void skip() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        /**
         * 判断读取位置是否已经到文本末尾。
         *
         * @return 没有剩余字符时为 true
         */
        private boolean done() {
            return index >= text.length();
        }
    }
}
