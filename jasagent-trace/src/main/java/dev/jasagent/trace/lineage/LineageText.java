package dev.jasagent.trace.lineage;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.regex.Pattern;

/**
 * 写入溯源表之前整理文本。
 * 空白和超长在这里拦住；Authorization、Bearer 和口令本体不会进库。
 */
final class LineageText {
    /**
     * 去掉 Authorization 和 Bearer 凭据的模式。
     * 先匹配带 Bearer 的整段，再匹配剩下的 Authorization，避免密钥留在字段里。
     */
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)authorization\\s*[:=]\\s*bearer\\s+\\S+|(?i)authorization\\s*[:=]\\s*\\S+|(?i)bearer\\s+\\S+");

    /**
     * 不构造实例。整理文本都走静态方法。
     */
    private LineageText() {
    }

    /**
     * 去掉首尾空白并检查长度。
     * 值为 null、空白或超过上限时抛出内部错误，消息里只有字段名，不带回原值。
     *
     * @param value 调用方传入的文本
     * @param field 字段名，写进异常消息
     * @param max 允许的最大字符数，含本数
     * @return 去掉首尾空白后的文本
     */
    static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new AgentException(ErrorCode.INTERNAL, field + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new AgentException(ErrorCode.INTERNAL, field + " 超过 " + max);
        }
        return trimmed;
    }

    /**
     * 先按 {@link #required} 检查，再去掉凭据和口令。
     * 去掉之后变成空白，说明原值就是密钥，抛出内部错误且不带回原值。
     *
     * @param value 调用方传入的文本
     * @param field 字段名，写进异常消息
     * @param max 允许的最大字符数
     * @param password 本机库口令；为空时只去掉 Authorization 和 Bearer
     * @return 可以入库的文本
     */
    static String requiredClean(String value, String field, int max, String password) {
        String cleaned = redact(required(value, field, max), password);
        if (cleaned.isBlank()) {
            throw new AgentException(ErrorCode.INTERNAL, field + " 含有不能入库的密钥");
        }
        return cleaned;
    }

    /**
     * 去掉口令本体、Authorization 和 Bearer。
     * 口令按字面先删，避免只被 Bearer 规则吃掉一半后还留下后半段。短于 8 个字符的口令不按子串删除，避免误伤普通单词。
     *
     * @param value 已经检查过的文本
     * @param password 本机库口令，可以为空
     * @return 去掉凭据后的文本
     */
    static String redact(String value, String password) {
        String cleaned = value;
        if (password != null && password.length() >= 8 && cleaned.contains(password)) {
            cleaned = cleaned.replace(password, "");
        }
        cleaned = AUTHORIZATION.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("(?i)\\bbearer\\b", "");
        return cleaned.trim();
    }
}
