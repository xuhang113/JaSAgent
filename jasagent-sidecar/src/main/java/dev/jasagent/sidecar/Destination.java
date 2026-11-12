package dev.jasagent.sidecar;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 一次出站要访问的绝对地址。规范形式会拿去和通行证上的目标做完全一致的比较，也会拿去真正拨号。
 * 用户信息会被拒绝，避免 {@code https://api.deepseek.com@evil.example} 这种写法混过主机判断。
 */
public final class Destination {
    private final String canonical;
    private final String host;

    /**
     * 保存规范地址和已经整理的主机。只由 {@link #parse(String)} 构造。
     *
     * @param canonical 发给 agentpass 和上游的绝对地址，不含片段和用户信息
     * @param host 小写主机，不含端口
     */
    private Destination(String canonical, String host) {
        this.canonical = canonical;
        this.host = host;
    }

    /**
     * 解析调用方给出的绝对地址。只接受 http 和 https。默认端口会从规范形式里去掉，片段丢掉，查询保留。
     * 因此证必须按这个规范形式签发；多一个查询参数就是另一个目标。
     *
     * @param raw {@code X-Jasagent-Url} 的原文
     * @return 可以转发的目的地；地址不合法时为空
     */
    public static Destination parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty() || hasBreak(text)) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException ex) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
            return null;
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
            return null;
        }
        String host = HostNames.canonical(uri.getHost());
        if (host == null) {
            return null;
        }
        int port = uri.getPort();
        boolean defaultPort = port < 0
                || ("https".equals(lowerScheme) && port == 443)
                || ("http".equals(lowerScheme) && port == 80);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (path.indexOf('\\') >= 0) {
            return null;
        }
        String query = uri.getRawQuery();
        StringBuilder canonical = new StringBuilder();
        canonical.append(lowerScheme).append("://");
        if (host.indexOf(':') >= 0) {
            canonical.append('[').append(host).append(']');
        } else {
            canonical.append(host);
        }
        if (!defaultPort) {
            canonical.append(':').append(port);
        }
        canonical.append(path);
        if (query != null && !query.isEmpty()) {
            canonical.append('?').append(query);
        }
        return new Destination(canonical.toString(), host);
    }

    /**
     * 判断原文里有没有空白、换行或空字符。这些字符不能出现在将要转发的绝对地址里。
     *
     * @param text 已经去掉两端空白的原文
     * @return 含有会切断地址的字符时为真
     */
    private static boolean hasBreak(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || ch == '\\' || ch == '\0') {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回拿去核销和拨号的规范地址。
     *
     * @return 绝对地址，主机是小写，默认端口已去掉
     */
    public String canonical() {
        return canonical;
    }

    /**
     * 返回用来对照放行名单的主机。
     *
     * @return 小写主机，不含端口和用户信息
     */
    public String host() {
        return host;
    }
}
