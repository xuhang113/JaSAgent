package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把检索文本切成词。
 * 表意文字逐字成词，连续字母或数字合成一个词，其它字符只充当分隔符。
 */
final class Tokenizer {
    /**
     * 分词没有实例状态，禁止被构造。
     * 副作用：不写入字段。
     */
    private Tokenizer() {
    }

    /**
     * 按阅读顺序抽出可用于比对的词。
     * 表意文字保留单个码点；字母和数字先转成小写再按连续片段合并；空白和标点被跳过。
     *
     * @param text 待分词的原文，空引用或空串没有词
     * @return 从左到右的词，不做去重
     */
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
