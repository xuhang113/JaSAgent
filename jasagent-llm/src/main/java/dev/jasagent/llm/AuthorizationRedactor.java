package dev.jasagent.llm;

import java.util.regex.Pattern;

/**
 * 从将要落日志、异常或追踪的文本里抹掉授权头和 Bearer 令牌。
 */
public final class AuthorizationRedactor {
    /**
     * 匹配 Authorization 头后面紧跟 Bearer 令牌的整段，冒号和等号都认，忽略大小写。
     */
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)authorization\\s*[:=]\\s*bearer\\s+\\S+");
    /**
     * 匹配任意 Authorization 头及其取值，用来清掉不是 Bearer 形式的授权串。
     */
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile("(?i)authorization\\s*[:=]\\s*\\S+");
    /**
     * 匹配正文里单独出现的 Bearer 令牌，不要求前面写着 Authorization。
     */
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+\\S+");

    /**
     * 禁止实例化。脱敏只通过静态方法使用。
     */
    private AuthorizationRedactor() {
    }

    /**
     * 去掉文本中的授权头和 Bearer 令牌，避免密钥跟着错误信息流出。
     *
     * @param text 可能含密钥的原文；null 或空白当作没有可处理的内容
     * @return 去掉授权信息后的文本；入参为 null 或空白时返回空串
     */
    public static String redact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = AUTHORIZATION.matcher(text).replaceAll("");
        cleaned = AUTHORIZATION_VALUE.matcher(cleaned).replaceAll("");
        cleaned = BEARER.matcher(cleaned).replaceAll("");
        return cleaned;
    }
}
