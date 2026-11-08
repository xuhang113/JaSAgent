package dev.jasagent.evaluation;

final class JudgeVerdicts {
    private JudgeVerdicts() {
    }

    static EvaluationResult toResult(String executionId, String content) {
        Parsed parsed = parse(content);
        if (parsed == null) {
            return new EvaluationResult(executionId, EvaluationEngine.LLM_JUDGE, false, 0.0, "judge-unparseable");
        }
        return new EvaluationResult(executionId, EvaluationEngine.LLM_JUDGE, parsed.passed, parsed.score, parsed.reason);
    }

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

    private record Parsed(boolean passed, double score, String reason) {
    }

    private static final class Cursor {
        private final String text;
        private int index;

        private Cursor(String text) {
            this.text = text;
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private char peek() {
            if (index >= text.length()) {
                return '\0';
            }
            return text.charAt(index);
        }

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
