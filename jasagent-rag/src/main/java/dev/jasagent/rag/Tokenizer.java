package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Tokenizer {
    private Tokenizer() {
    }

    static List<String> tokens(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        int index = 0;
        while (index < lower.length()) {
            int codePoint = lower.codePointAt(index);
            int width = Character.charCount(codePoint);
            if (Character.isIdeographic(codePoint)) {
                tokens.add(new String(Character.toChars(codePoint)));
                index += width;
                continue;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                int start = index;
                index += width;
                while (index < lower.length()) {
                    int next = lower.codePointAt(index);
                    if (Character.isIdeographic(next) || !Character.isLetterOrDigit(next)) {
                        break;
                    }
                    index += Character.charCount(next);
                }
                tokens.add(lower.substring(start, index));
                continue;
            }
            index += width;
        }
        return tokens;
    }
}
