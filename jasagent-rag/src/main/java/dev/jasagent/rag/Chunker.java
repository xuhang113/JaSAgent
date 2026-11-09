package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一篇文档先按 Markdown 标题切开，再把过长的节滑成固定窗口。
 * 窗口之间保留重叠，避免一句话刚好被切在边界上后无法命中。
 */
public final class Chunker {
    /** 一段最多容纳的字符数。不超过它的整节直接成为一段。 */
    static final int WINDOW = 800;
    /** 相邻滑动窗口重叠的字符数，用来保住跨在切点上的句子。 */
    static final int OVERLAP = 100;
    /** 下一个窗口起点相对上一个窗口前进的字符数，即 800 减去 100。 */
    static final int STEP = WINDOW - OVERLAP;
    /** 匹配 Markdown 的 ATX 标题行，捕获 1 到 6 个井号以及去掉两端空白后的标题文字。 */
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})[ \\t]+(.+?)[ \\t]*$");

    /**
     * 切段不保留跨文档状态。
     * 副作用：不写入字段。
     */
    private Chunker() {
    }

    /**
     * 把一篇文档切成带标题和原文下标的段。
     * 空文本按空串处理。没有标题时用文件名去掉扩展名当标题；超长节从节起点每 700 个字符再开一段，每段最多 800 个字符。
     *
     * @param uri 文档相对路径，同时用来生成段标识和缺标题时的标题
     * @param text 文档全文，空引用当成空文档
     * @return 按文中出现顺序排列的段；空文档没有段
     */
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

    /**
     * 按原文下标截出一段，段标识用路径、井号和本篇内序号组成。
     *
     * @param uri 文档相对路径
     * @param number 这段在本篇文档中的序号，从 0 递增
     * @param title 这段所属标题
     * @param start 起始下标，包含该位置
     * @param end 结束下标，不包含该位置
     * @param text 截取用的全文
     * @return 正文等于全文对应切片的段
     */
    private static TextChunk piece(String uri, int number, String title, int start, int end, String text) {
        return new TextChunk(uri, title, uri + "#" + number, start, end, text.substring(start, end));
    }

    /**
     * 按标题行把全文分成节。
     * 标题之前的文字沿用上一节标题，开篇没有标题时用回退标题；每一节从它的标题行起，到下一标题行之前止。
     *
     * @param fallbackTitle 文首还没有标题时使用的标题，通常是文件名
     * @param text 文档全文
     * @return 按文中顺序排列的节；没有标题时只有覆盖全文的一节
     */
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

    /**
     * 从文档路径取出不带扩展名的文件名，当作没有标题时的标题。
     *
     * @param uri 文档路径，空引用当成空路径
     * @return 最后一段路径去掉扩展名后的名字；没有文件名时为空串
     */
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

    /**
     * 标题切分后的一节，后面还可能再按窗口滑开。
     *
     * @param title 这一节使用的标题
     * @param start 节在全文中的起始下标，包含标题行
     * @param end 节在全文中的结束下标，不包含该位置
     */
    private record Section(String title, int start, int end) {
    }
}
