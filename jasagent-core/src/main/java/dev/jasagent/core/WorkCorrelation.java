package dev.jasagent.core;

import dev.jasagent.api.task.WorkItem;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 给两条待办估算是否该交给同一个子代理，会话相同优先，其次关联键，最后看目标文本。
 */
public final class WorkCorrelation {
    /**
     * 禁止实例化，相关度只通过静态计算得到。
     */
    private WorkCorrelation() {
    }

    /**
     * 计算两条待办的相关度。会话标识相同为 1，否则关联键相同为 0.9，再否则用目标文本的词集合交并比。
     *
     * @param left 第一条待办
     * @param right 第二条待办
     * @return 零到一之间的相关度，空白会话或关联键不算相同
     */
    public static double score(WorkItem left, WorkItem right) {
        if (same(left.sessionId(), right.sessionId())) {
            return 1.0;
        }
        if (same(left.correlationKey(), right.correlationKey())) {
            return 0.9;
        }
        return jaccard(left.targetText(), right.targetText());
    }

    /**
     * 判断两个标识是否都有内容且完全相同，区分大小写，空白不算相同。
     *
     * @param left 左侧标识
     * @param right 右侧标识
     * @return 两侧都非空白且相等时为真
     */
    private static boolean same(String left, String right) {
        return left != null && right != null && !left.isBlank() && left.equals(right);
    }

    /**
     * 用两侧目标文本的词集合计算交并比。任一侧没有词时相关度为零。
     *
     * @param left 左侧目标文本
     * @param right 右侧目标文本
     * @return 交集大小除以并集大小
     */
    private static double jaccard(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        for (String token : leftTokens) {
            if (rightTokens.contains(token)) {
                intersection++;
            }
        }
        int union = leftTokens.size() + rightTokens.size() - intersection;
        return intersection / (double) union;
    }

    /**
     * 把文本按大小写不敏感的字母数字段切成词，重复词只留一次。
     *
     * @param text 目标文本；空则没有词
     * @return 按出现顺序去重后的词
     */
    private static Set<String> tokens(String text) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < lower.length(); index++) {
            char ch = lower.charAt(index);
            if (Character.isLetterOrDigit(ch)) {
                current.append(ch);
            } else if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
