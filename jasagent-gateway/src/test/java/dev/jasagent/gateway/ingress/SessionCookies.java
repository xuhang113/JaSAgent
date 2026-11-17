package dev.jasagent.gateway.ingress;

/**
 * 检查会话 Cookie 的属性。按分号拆开，避免令牌正文里碰巧出现 Secure 这几个字母。
 */
final class SessionCookies {
    /**
     * 禁止实例化。检查只通过静态方法做。
     */
    private SessionCookies() {
    }

    /**
     * 签发或清除的会话 Cookie 必须是 HttpOnly、path=/、SameSite=Lax。Secure 由调用方指定。
     *
     * @param header Set-Cookie 原文
     * @param secure 是否必须带 Secure
     * @param clearing 是否必须立刻过期
     * @param secret 不应出现在头里的口令；没有时为空
     */
    static void assertSession(String header, boolean secure, boolean clearing, String secret) {
        org.junit.jupiter.api.Assertions.assertNotNull(header, "没有会话 Cookie");
        org.junit.jupiter.api.Assertions.assertTrue(header.startsWith("JASAGENT_SESSION="), "会话 Cookie 名字不对");
        org.junit.jupiter.api.Assertions.assertTrue(has(header, "HttpOnly"), "会话 Cookie 缺少 HttpOnly");
        org.junit.jupiter.api.Assertions.assertTrue(has(header, "Path=/"), "会话 Cookie 路径不是 /");
        org.junit.jupiter.api.Assertions.assertTrue(has(header, "SameSite=Lax"), "会话 Cookie 的 SameSite 不是 Lax");
        org.junit.jupiter.api.Assertions.assertEquals(secure, has(header, "Secure"), "Secure 与 HTTPS 认定不一致");
        if (clearing) {
            org.junit.jupiter.api.Assertions.assertTrue(has(header, "Max-Age=0"), "清除 Cookie 没有立刻过期");
        }
        if (secret != null) {
            org.junit.jupiter.api.Assertions.assertFalse(header.contains(secret), "Cookie 含有口令");
        }
    }

    /**
     * 某个属性是否单独出现。比较的是分号后面的整段，不是令牌里的子串。
     *
     * @param header Set-Cookie 原文
     * @param attribute 属性原文
     * @return 有这个属性时为真
     */
    private static boolean has(String header, String attribute) {
        for (String part : header.split(";")) {
            if (attribute.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }
}
