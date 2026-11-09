package dev.jasagent.app;

import java.util.regex.Pattern;

/**
 * 判断一句用户话要不要另开子 agent。
 * 只有会产出结果物的工作才返回 true；问候、解释和一句话能答完的留在主会话。
 */
public final class SubAgentNeed {
    /**
     * 需要工作区事实或检索结果的说法。命中才开子 agent。
     */
    private static final Pattern ARTIFACT = Pattern.compile(
            "文件|目录|工作区|仓库|读取|读一下|列出|查找|搜索|检索|统计|pom\\.xml|readme|有多少|几个文件|源码|代码里|grep|glob|list_directory",
            Pattern.CASE_INSENSITIVE);

    /** 只通过静态方法使用，不构造实例。 */
    private SubAgentNeed() {
    }

    /**
     * 这句是不是要单独做出一个结果。
     *
     * @param text 用户原文
     * @return 需要结果物时为 true；问候、能力说明和空文本为 false
     */
    public static boolean required(String text) {
        if (text == null) {
            return false;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return false;
        }
        return ARTIFACT.matcher(value).find();
    }
}
