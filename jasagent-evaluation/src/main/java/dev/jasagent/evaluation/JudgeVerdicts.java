package dev.jasagent.evaluation;

/**
 * 把模型评判返回的 JSON 收成评估结果；解析不了时记为未通过。
 */
final class JudgeVerdicts {
    /**
     * 禁止实例化，裁决只通过静态转换使用。
     */
    private JudgeVerdicts() {
    }

    /**
     * 把模型回复转成评判结果。不是单一 JSON 对象，或字段缺失、超范围时，分数为零并标明无法解析。
     *
     * @param executionId 被评判的执行
     * @param content 模型回复的正文
     * @return 解析成功时带上模型给出的通过、分数和原因；否则为无法解析
     */
    static EvaluationResult toResult(String executionId, String content) {
        Parsed parsed = parse(content);
        if (parsed == null) {
            return new EvaluationResult(executionId, EvaluationEngine.LLM_JUDGE, false, 0.0, "judge-unparseable");
        }
        return new EvaluationResult(executionId, EvaluationEngine.LLM_JUDGE, parsed.passed, parsed.score, parsed.reason);
    }

    /**
     * 只接受由 passed、score 和 reason 组成的单个 JSON 对象，拒绝多余字段、重复字段和分数越界。
     *
     * @param content 模型回复；空或不是对象时无法解析
     * @return 三个字段都合法时的裁决；否则为空
     */
    private static Parsed parse(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        if (text.length() < 2 || text.charAt(0) != '{' || text.charAt(text.length() - 1) != '}') {
            return null;
        }
        Cursor cursor = new Cursor(text);
        cursor.index = 1;
        Boolean passed = null;
        Double score = null;
        String reason = null;
        boolean seenField = false;
        while (true) {
            cursor.skipWhitespace();
            if (cursor.peek() == '}') {
                break;
            }
            if (seenField) {
                if (cursor.peek() != ',') {
                    return null;
                }
                cursor.index++;
                cursor.skipWhitespace();
            }
            String key = cursor.readString();
            if (key == null) {
                return null;
            }
            cursor.skipWhitespace();
            if (cursor.peek() != ':') {
                return null;
            }
            cursor.index++;
            cursor.skipWhitespace();
            switch (key) {
                case "passed" -> {
                    if (passed != null) {
                        return null;
                    }
                    passed = cursor.readBoolean();
                    if (passed == null) {
                        return null;
                    }
                }
                case "score" -> {
                    if (score != null) {
                        return null;
                    }
                    score = cursor.readNumber();
                    if (score == null || !Double.isFinite(score) || score < 0.0 || score > 1.0) {
                        return null;
                    }
                }
                case "reason" -> {
                    if (reason != null) {
                        return null;
                    }
                    reason = cursor.readString();
                    if (reason == null) {
                        return null;
                    }
                }
                default -> {
                    return null;
                }
            }
            seenField = true;
        }
        if (cursor.index != text.length() - 1 || passed == null || score == null || reason == null) {
            return null;
        }
        return new Parsed(passed, score, reason);
    }

    /**
     * 从评判 JSON 中读出的三个字段。
     *
     * @param passed 模型是否认为通过
     * @param score 零到一之间的有限分数
     * @param reason 模型给出的原因
     */
    private record Parsed(boolean passed, double score, String reason) {
    }

    /**
     * 在一段 JSON 文本上向前读取空白、布尔、数字和字符串。
     */
    private static final class Cursor {
        private final String text;
        private int index;

        /**
         * 记住这段回复，并从第一个字符开始扫描。
         *
         * @param text 去掉首尾空白后的模型回复
         */
        private Cursor(String text) {
            this.text = text;
        }

        /**
         * 把扫描位置移过连续空白。副作用是推进当前位置。
         */
        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        /**
         * 查看当前位置的字符，不向前移动。
         *
         * @return 当前字符；已经到末尾时为空字符
         */
        private char peek() {
            if (index >= text.length()) {
                return '\0';
            }
            return text.charAt(index);
        }

        /**
         * 读取 true 或 false，并在成功时把位置移到值之后。
         *
         * @return 读到的布尔值；当前位置不是布尔字面量时为空
         */
        private Boolean readBoolean() {
            if (text.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            return null;
        }

        /**
         * 读取可选负号、整数和小数，拒绝指数记法。成功时把位置移到数字之后。
         *
         * @return 读到的有限数字；格式不符时为空
         */
        private Double readNumber() {
            int start = index;
            if (peek() == '-') {
                index++;
            }
            boolean digit = false;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                digit = true;
                index++;
            }
            if (peek() == '.') {
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    digit = true;
                    index++;
                }
            }
            if (!digit || peek() == 'e' || peek() == 'E') {
                return null;
            }
            try {
                return Double.valueOf(text.substring(start, index));
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        /**
         * 读取 JSON 字符串，解开引号、反斜杠、换行和制表符转义，并在成功时把位置移到结束引号之后。
         *
         * @return 解码后的文本；引号不配对或遇到不支持的转义时为空
         */
        private String readString() {
            if (peek() != '"') {
                return null;
            }
            index++;
            StringBuilder builder = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index);
                if (current == '"') {
                    index++;
                    return builder.toString();
                }
                if (current == '\\') {
                    index++;
                    if (index >= text.length()) {
                        return null;
                    }
                    char escaped = text.charAt(index);
                    switch (escaped) {
                        case '"', '\\', '/' -> builder.append(escaped);
                        case 'n' -> builder.append('\n');
                        case 't' -> builder.append('\t');
                        default -> {
                            return null;
                        }
                    }
                    index++;
                    continue;
                }
                if (current < 0x20) {
                    return null;
                }
                builder.append(current);
                index++;
            }
            return null;
        }
    }
}
