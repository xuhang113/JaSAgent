package dev.jasagent.auth;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 Markdown 开头的简易前言拆成键值元数据和正文。
 */
final class FrontMatter {
    private final Map<String, String> meta;
    private final String body;

    /**
     * 保存一次解析得到的元数据和正文。
     *
     * @param meta 前言中的键值，解析失败时为空映射
     * @param body 去掉前言后的正文
     */
    private FrontMatter(Map<String, String> meta, String body) {
        this.meta = meta;
        this.body = body;
    }

    /**
     * 识别以分隔线包裹的前言；没有前言或结束分隔线缺失时，整段文本都当作正文。
     *
     * @param text 规则或技能文件的全文，允许使用回车换行
     * @return 元数据与正文分离后的结果
     */
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

    /**
     * 返回前言里的键值，例如规则标识和说明。
     *
     * @return 解析出的元数据；没有前言时为空映射
     */
    Map<String, String> meta() {
        return meta;
    }

    /**
     * 返回去掉前言并去掉首尾空白后的正文。
     *
     * @return 文档正文
     */
    String body() {
        return body;
    }
}
