package dev.jasagent.sidecar;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 把调用方给的主机收成可以比较的小写主机名。比较时只认完整主机，不认后缀或通配符，
 * 因此 {@code evil.api.deepseek.com} 和 {@code api.deepseek.com.evil.com} 都不会命中 {@code api.deepseek.com}。
 */
final class HostNames {
    /**
     * 主机名最长按 DNS 的 253 个字符计算，避免把一整段地址当成主机。
     */
    private static final int MAX_HOST = 253;
    /**
     * 单个标签最长 63 个字符，和 DNS 标签限制一致。
     */
    private static final int MAX_LABEL = 63;

    /**
     * 禁止实例化。主机名只通过静态方法整理。
     */
    private HostNames() {
    }

    /**
     * 整理已经解析出来的主机。去掉两端空白、方括号和末尾的点，再检查字符合法。
     *
     * @param host URI 解析出的主机，可以带方括号，不应再带端口
     * @return 小写主机；空、过长或含通配符时为空
     */
    static String canonical(String host) {
        if (host == null) {
            return null;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        if (!acceptable(value)) {
            return null;
        }
        return value;
    }

    /**
     * 从放行名单里的一项取出主机。可以是纯主机、{@code 主机:端口}，或只取其中主机的绝对 URL。
     * 含通配符、反斜线或用户信息的项丢掉，不让一条配置把整段后缀放行。
     *
     * @param token 名单里的一项原文
     * @return 小写主机；这一项不能用时为空
     */
    static String fromToken(String token) {
        if (token == null) {
            return null;
        }
        String value = token.trim();
        if (value.isEmpty() || value.indexOf('*') >= 0 || value.indexOf('\\') >= 0) {
            return null;
        }
        if (value.contains("://")) {
            try {
                URI uri = new URI(value);
                if (uri.getUserInfo() != null) {
                    return null;
                }
                return canonical(uri.getHost());
            } catch (URISyntaxException ex) {
                return null;
            }
        }
        if (value.indexOf('/') >= 0) {
            return null;
        }
        String bare = value.toLowerCase(Locale.ROOT);
        int colon = bare.lastIndexOf(':');
        if (colon > 0 && bare.indexOf(':') == colon) {
            String port = bare.substring(colon + 1);
            if (!port.isEmpty() && port.chars().allMatch(Character::isDigit)) {
                bare = bare.substring(0, colon);
            }
        }
        return canonical(bare);
    }

    /**
     * 判断主机是不是只含 DNS 标签或 IPv6 十六进制。不接受空标签、星号和百分号。
     *
     * @param host 已经去掉空白和末尾点的小写主机
     * @return 可以放进名单或拿去比较时为真
     */
    static boolean acceptable(String host) {
        if (host == null || host.isEmpty() || host.length() > MAX_HOST || host.indexOf('%') >= 0) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            for (int i = 0; i < host.length(); i++) {
                char ch = host.charAt(i);
                boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || ch == ':';
                if (!hex) {
                    return false;
                }
            }
            return true;
        }
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > MAX_LABEL) {
                return false;
            }
            if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char ch = label.charAt(i);
                boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-';
                if (!ok) {
                    return false;
                }
            }
        }
        return true;
    }
}
