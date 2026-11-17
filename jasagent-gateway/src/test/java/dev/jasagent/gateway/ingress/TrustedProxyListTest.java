package dev.jasagent.gateway.ingress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受信任名单只认 IP 和 CIDR。空白名单不信任回环、私网或客户端写来的协议。
 */
class TrustedProxyListTest {
    /**
     * 默认空白时，回环、IPv6 回环和集群网段都不被信任，伪造的 https 也不算数。
     */
    @Test
    void blankListTrustsNobody() {
        TrustedProxyList empty = new TrustedProxyList(null);
        TrustedProxyList blank = new TrustedProxyList("  , , ");
        for (TrustedProxyList list : new TrustedProxyList[] {empty, blank}) {
            assertFalse(list.trusts("127.0.0.1"));
            assertFalse(list.trusts("::1"));
            assertFalse(list.trusts("10.42.1.8"));
            assertFalse(list.trusts("192.168.1.1"));
            assertFalse(list.trusts("localhost"));
            assertFalse(list.https("127.0.0.1", "https"));
            assertFalse(list.https("10.42.1.8", "https"));
            assertEquals("", list.declaredScheme("10.42.1.8", "https"));
            assertFalse(list.toString().contains("10.42"));
        }
    }

    /**
     * IPv4 网段含边界，不含相邻网段。精确地址只命中自己。映射的 IPv6 可以命中 IPv4 网段。
     */
    @Test
    void cidrAndExactAddressMatchTheSocketPeer() {
        TrustedProxyList list = new TrustedProxyList(" 10.42.0.0/16, 192.0.2.10 , ::ffff:10.8.0.0/112 ");
        assertTrue(list.trusts("10.42.0.0"));
        assertTrue(list.trusts("10.42.255.255"));
        assertTrue(list.trusts("10.42.1.8"));
        assertFalse(list.trusts("10.43.0.0"));
        assertFalse(list.trusts("10.41.255.255"));
        assertFalse(list.trusts("11.42.0.1"));
        assertTrue(list.trusts("192.0.2.10"));
        assertFalse(list.trusts("192.0.2.11"));
        assertTrue(list.trusts("::ffff:10.42.1.8"));
        assertTrue(list.trusts("10.8.1.9"));
        assertTrue(list.trusts("::ffff:10.8.1.9"));
        assertFalse(list.trusts("10.9.0.1"));
        assertFalse(list.toString().contains("10.42"));
        assertFalse(list.toString().contains("192.0.2.10"));
        assertTrue(list.toString().contains("rules="));
    }

    /**
     * IPv6 前缀、压缩写法和网卡后缀按同一地址比较。方括号里的回环也认。
     */
    @Test
    void ipv6PrefixesAndZonesMatch() {
        TrustedProxyList list = new TrustedProxyList("2001:db8::/32, fe80::1, ::1/128");
        assertTrue(list.trusts("2001:db8::1"));
        assertTrue(list.trusts("2001:db8:0:0:0:0:0:1"));
        assertTrue(list.trusts("2001:DB8::1"));
        assertFalse(list.trusts("2001:db9::1"));
        assertTrue(list.trusts("fe80::1"));
        assertTrue(list.trusts("fe80::1%eth0"));
        assertFalse(list.trusts("fe80::2"));
        assertTrue(list.trusts("::1"));
        assertTrue(list.trusts("[::1]"));
        assertFalse(list.trusts("::2"));
    }

    /**
     * 协议只看受信任套接字上的最右侧一段。大小写不敏感。客户端写在前面的 https 不能盖过这一跳的 http。
     */
    @Test
    void rightmostSchemeComesFromTheTrustedHop() {
        TrustedProxyList list = new TrustedProxyList("10.42.0.0/16");
        assertTrue(list.https("10.42.1.8", "https"));
        assertTrue(list.https("10.42.1.8", " HTTPS "));
        assertTrue(list.https("10.42.1.8", "http, https"));
        assertEquals("https", list.declaredScheme("10.42.1.8", "http, HTTPS"));
        assertFalse(list.https("10.42.1.8", "https, http"));
        assertEquals("http", list.declaredScheme("10.42.1.8", "https, http"));
        assertEquals("", list.declaredScheme("10.42.1.8", ""));
        assertEquals("", list.declaredScheme("10.42.1.8", "wss"));
        assertFalse(list.https("203.0.113.8", "https"));
        assertFalse(list.https("203.0.113.8", "http, https"));
        assertEquals("", list.declaredScheme("203.0.113.8", "https"));
    }

    /**
     * 主机名、过宽前缀和非法 CIDR 在启动时拒绝。异常不带回原文，也不挂 cause。
     */
    @Test
    void invalidEntriesFailWithoutEchoingTheToken() {
        String credential = "not-a-forwarded-credential";
        IllegalStateException invalid = assertThrows(IllegalStateException.class, () -> new TrustedProxyList(credential));
        assertEquals("受信任转发地址必须是 IP 或 CIDR", invalid.getMessage());
        assertFalse(invalid.getMessage().contains(credential));
        assertNull(invalid.getCause());
        assertEquals("受信任转发地址必须是 IP 或 CIDR",
                assertThrows(IllegalStateException.class, () -> new TrustedProxyList("10.42.0.0/33")).getMessage());
        assertEquals("受信任转发地址必须是 IP 或 CIDR",
                assertThrows(IllegalStateException.class, () -> new TrustedProxyList("10.01.0.0/16")).getMessage());
        assertEquals("受信任转发地址不能覆盖全部地址",
                assertThrows(IllegalStateException.class, () -> new TrustedProxyList("0.0.0.0/0")).getMessage());
        assertEquals("受信任转发地址不能覆盖全部地址",
                assertThrows(IllegalStateException.class, () -> new TrustedProxyList("::/0")).getMessage());
        assertEquals("受信任转发地址不能覆盖全部地址",
                assertThrows(IllegalStateException.class, () -> new TrustedProxyList("10.0.0.0/0")).getMessage());
    }
}
