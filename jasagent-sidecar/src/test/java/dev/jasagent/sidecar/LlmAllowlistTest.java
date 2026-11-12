package dev.jasagent.sidecar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对免证名单只认完整主机，额外名单来自调用方传入的环境变量原文。
 */
class LlmAllowlistTest {
    /**
     * 空的额外名单仍然放行两家大模型主机，大小写和末尾的点不影响。
     */
    @Test
    void builtinModelHostsAreAllowlisted() {
        LlmAllowlist allowlist = LlmAllowlist.parse("");
        assertTrue(allowlist.permits("api.deepseek.com"));
        assertTrue(allowlist.permits("API.DeepSeek.COM."));
        assertTrue(allowlist.permits("api.x.ai"));
        assertTrue(allowlist.permits("API.X.AI"));
        assertFalse(allowlist.permits("x.ai"));
        assertFalse(allowlist.permits("api.deepseek.com.evil.com"));
        assertFalse(allowlist.permits("evil.api.deepseek.com"));
        assertFalse(allowlist.permits("not-api.x.ai"));
        assertFalse(allowlist.permits("example.com"));
    }

    /**
     * 额外项可以是主机、带端口的主机或绝对 URL，只取主机。通配符和说不清的项不进入名单。
     */
    @Test
    void extraHostsComeFromAllowlistText() {
        LlmAllowlist allowlist = LlmAllowlist.parse(" hooks.example.com, api.openai.com:443 \nhttps://files.example.com/ignored *.evil.com ");
        assertTrue(allowlist.permits("hooks.example.com"));
        assertTrue(allowlist.permits("api.openai.com"));
        assertTrue(allowlist.permits("files.example.com"));
        assertTrue(allowlist.permits(LlmAllowlist.DEEPSEEK_HOST));
        assertTrue(allowlist.permits(LlmAllowlist.XAI_HOST));
        assertFalse(allowlist.permits("*.evil.com"));
        assertFalse(allowlist.permits("evil.com"));
        assertFalse(allowlist.permits("ignored"));
    }

    /**
     * 规范地址去掉默认端口和片段，保留查询，并拒绝用户信息。目标比对用的就是这个字符串。
     */
    @Test
    void destinationCanonicalizesHostAndDropsUserInfo() {
        Destination deepseek = Destination.parse("https://API.DeepSeek.COM/chat/completions");
        assertEquals("https://api.deepseek.com/chat/completions", deepseek.canonical());
        assertEquals("api.deepseek.com", deepseek.host());
        Destination port = Destination.parse("http://example.com:80/a#frag");
        assertEquals("http://example.com/a", port.canonical());
        Destination query = Destination.parse("https://example.com:8443/hook?x=1");
        assertEquals("https://example.com:8443/hook?x=1", query.canonical());
        assertNull(Destination.parse("https://api.deepseek.com@evil.example/chat"));
        assertNull(Destination.parse("file:///tmp/secret"));
        assertNull(Destination.parse("https://example.com/a b"));
    }
}
