package dev.jasagent.gateway.ingress;

import java.util.ArrayList;
import java.util.List;

/**
 * 受信任的 Ingress-nginx / Funnel 这一跳。
 * 只认直接连到网关的套接字地址，不认客户端写在 X-Forwarded-For 里的地址。
 * 名单默认空白：本机明文、集群内直连，以及任意客户端自带的 X-Forwarded-*，都不会被当成 HTTPS。
 * 部署把 Ingress 这一跳的 IP 或 CIDR 配到 {@link #PROPERTY}，不要写成 0.0.0.0/0，也不要写公网主机名或凭证。
 */
public final class TrustedProxyList {
    /**
     * 配置项。逗号分隔的 IPv4、IPv6 或 CIDR。空白表示谁都不信任。
     */
    public static final String PROPERTY = "jasagent.gateway.forwarded.trusted";
    /**
     * 覆盖 {@link #PROPERTY} 的环境变量。Spring 也会按宽松绑定读到同一项。
     */
    public static final String ENVIRONMENT = "JASAGENT_GATEWAY_FORWARDED_TRUSTED";

    private final List<Rule> rules;

    /**
     * 解析受信任名单。空白合法，表示不信任任何一跳。非法地址在启动时拒绝，异常里不回写原文。
     *
     * @param raw 逗号分隔的 IP 或 CIDR；可以是空
     */
    public TrustedProxyList(String raw) {
        this.rules = parse(raw);
    }

    /**
     * 直接连上来的地址是否落在名单里。空地址、主机名和非法写法都不信任，也不做 DNS。
     *
     * @param remoteAddress 套接字对端，一般是 {@code getRemoteAddr()}
     * @return 这一跳受信任时为真
     */
    public boolean trusts(String remoteAddress) {
        byte[] address = parseLiteral(remoteAddress);
        if (address == null) {
            return false;
        }
        for (Rule rule : rules) {
            if (rule.matches(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 受信任且 {@code X-Forwarded-Proto} 最右侧一段是 https 时为真。不受信任时忽略协议头。
     *
     * @param remote 套接字对端
     * @param forwardedProto 协议头原文，多段用逗号分隔
     * @return 应把这次请求看成 HTTPS 时为真
     */
    public boolean https(String remote, String forwardedProto) {
        return "https".equals(declaredScheme(remote, forwardedProto));
    }

    /**
     * 受信任时返回这一跳声明的协议，只可能是 https、http 或空串。
     * 不受信任、或最右侧一段不是 http/https 时为空串，调用方应保留连接自己的协议。
     * 多段取值时只看最后一段，这样 Ingress 追加的 {@code $scheme} 盖过客户端事先塞进来的值。
     *
     * @param remote 套接字对端
     * @param forwardedProto 协议头原文
     * @return https、http 或空串
     */
    public String declaredScheme(String remote, String forwardedProto) {
        if (!trusts(remote)) {
            return "";
        }
        String token = rightmost(forwardedProto);
        if ("https".equalsIgnoreCase(token)) {
            return "https";
        }
        if ("http".equalsIgnoreCase(token)) {
            return "http";
        }
        return "";
    }

    /**
     * 不打印地址。名单里如果被误写入口令或令牌，也不能从日志里读出来。
     *
     * @return 只含条数的说明
     */
    @Override
    public String toString() {
        return "TrustedProxyList{rules=" + rules.size() + "}";
    }

    /**
     * 取逗号分隔转发头的最后一段。空段丢掉。
     *
     * @param raw 头原文；可以是空
     * @return 最后一段；没有时为空串
     */
    static String rightmost(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String last = "";
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                last = trimmed;
            }
        }
        return last;
    }

    /**
     * 把字面量 IP 收成字节。主机名、空串和非法写法返回空，不做 DNS。
     * IPv4 映射的 IPv6 会收成 16 字节映射形式，匹配时再和 IPv4 网段互认。
     *
     * @param text 可能是 IP 的文本
     * @return 地址字节；不是字面量 IP 时为空
     */
    static byte[] parseLiteral(String text) {
        return parseIp(text);
    }

    /**
     * 把配置收成不可变名单。空配置得到空名单，不表示信任全部地址。
     *
     * @param raw 配置原文
     * @return 受信任网段
     */
    private static List<Rule> parse(String raw) {
        List<Rule> parsed = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return List.copyOf(parsed);
        }
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (!token.isEmpty()) {
                parsed.add(rule(token));
            }
        }
        return List.copyOf(parsed);
    }

    /**
     * 解析一条 IP 或 CIDR。前缀 0 会信任全部地址，直接拒绝。异常不包含原文。
     *
     * @param token 已经去掉首尾空白的一条
     * @return 网段
     */
    private static Rule rule(String token) {
        int slash = token.indexOf('/');
        String addressText = slash < 0 ? token : token.substring(0, slash).trim();
        String prefixText = slash < 0 ? null : token.substring(slash + 1).trim();
        if (slash >= 0 && (addressText.isEmpty() || prefixText.isEmpty() || token.indexOf('/', slash + 1) >= 0)) {
            throw invalid();
        }
        byte[] parsed = parseIp(addressText);
        if (parsed == null) {
            throw invalid();
        }
        int bits = prefixText == null ? parsed.length * 8 : prefix(prefixText, parsed.length * 8);
        byte[] mapped = unmapped(parsed);
        if (mapped != null) {
            if (bits < 96) {
                throw tooWide();
            }
            bits = bits - 96;
            if (bits == 0 || bits > 32) {
                throw invalid();
            }
            parsed = mapped;
        }
        return new Rule(parsed, bits);
    }

    /**
     * 解析前缀长度。0 单独拒绝，避免一条配置信任整个网络。
     *
     * @param text 前缀原文
     * @param max 该地址族允许的最大前缀
     * @return 1 到 max 的前缀
     */
    private static int prefix(String text, int max) {
        if (text.isEmpty() || text.length() > 3 || (text.length() > 1 && text.charAt(0) == '0')) {
            throw invalid();
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw invalid();
            }
            value = value * 10 + (c - '0');
        }
        if (value == 0) {
            throw tooWide();
        }
        if (value > max) {
            throw invalid();
        }
        return value;
    }

    /**
     * 解析字面量 IP。支持去掉方括号和网卡后缀。不调用解析器做 DNS。
     *
     * @param text 地址原文
     * @return 4 或 16 字节；无法解析时为空
     */
    private static byte[] parseIp(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        if (value.indexOf(':') >= 0) {
            return parseV6(value);
        }
        if (value.indexOf('.') >= 0) {
            return parseV4(value);
        }
        return null;
    }

    /**
     * 解析 IPv4。拒绝缺段、超出 255 和前导零，避免把八进制写法当成十进制。
     *
     * @param text 点分地址
     * @return 4 字节；非法时为空
     */
    private static byte[] parseV4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
                return null;
            }
            int value = 0;
            for (int index = 0; index < part.length(); index++) {
                char c = part.charAt(index);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            out[i] = (byte) value;
        }
        return out;
    }

    /**
     * 解析 IPv6，包括末尾嵌的 IPv4。压缩零只能出现一次。
     *
     * @param text 冒号分隔的地址，可以含一次 ::
     * @return 16 字节；非法时为空
     */
    private static byte[] parseV6(String text) {
        String source = text;
        int dot = source.lastIndexOf('.');
        if (dot >= 0) {
            int colon = source.lastIndexOf(':');
            if (colon < 0 || colon > dot) {
                return null;
            }
            byte[] v4 = parseV4(source.substring(colon + 1));
            if (v4 == null) {
                return null;
            }
            String high = Integer.toHexString(((v4[0] & 0xff) << 8) | (v4[1] & 0xff));
            String low = Integer.toHexString(((v4[2] & 0xff) << 8) | (v4[3] & 0xff));
            source = source.substring(0, colon + 1) + high + ":" + low;
        }
        int compact = source.indexOf("::");
        if (compact >= 0 && compact != source.lastIndexOf("::")) {
            return null;
        }
        String head = compact >= 0 ? source.substring(0, compact) : source;
        String tail = compact >= 0 ? source.substring(compact + 2) : null;
        if (compact >= 0 && (head.endsWith(":") || tail.startsWith(":"))) {
            return null;
        }
        String[] left = splitGroups(head);
        String[] right = tail == null ? null : splitGroups(tail);
        if (left == null || (tail != null && right == null)) {
            return null;
        }
        int total = left.length + (right == null ? 0 : right.length);
        if (tail == null && total != 8) {
            return null;
        }
        if (tail != null && total > 7) {
            return null;
        }
        int[] groups = new int[8];
        for (int i = 0; i < left.length; i++) {
            groups[i] = hexGroup(left[i]);
            if (groups[i] < 0) {
                return null;
            }
        }
        if (right != null) {
            for (int i = 0; i < right.length; i++) {
                int value = hexGroup(right[i]);
                if (value < 0) {
                    return null;
                }
                groups[8 - right.length + i] = value;
            }
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i * 2] = (byte) (groups[i] >> 8);
            out[i * 2 + 1] = (byte) groups[i];
        }
        return out;
    }

    /**
     * 按冒号拆组。空组表示写法非法，压缩零不走这里。
     *
     * @param text 不含 :: 的半边
     * @return 组；有空组时为空
     */
    private static String[] splitGroups(String text) {
        if (text.isEmpty()) {
            return new String[0];
        }
        String[] parts = text.split(":", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                return null;
            }
        }
        return parts;
    }

    /**
     * 解析 1 到 4 位十六进制。非法时返回 -1。
     *
     * @param text 一组十六进制
     * @return 0 到 65535；非法时为 -1
     */
    private static int hexGroup(String text) {
        if (text.length() < 1 || text.length() > 4) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit;
            if (c >= '0' && c <= '9') {
                digit = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                digit = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                digit = c - 'A' + 10;
            } else {
                return -1;
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    /**
     * 取出 IPv4 映射地址的后 4 字节。不是映射地址时为空。
     *
     * @param address 16 字节的 IPv6
     * @return IPv4；不是 ::ffff:0:0/96 时为空
     */
    private static byte[] unmapped(byte[] address) {
        if (address == null || address.length != 16) {
            return null;
        }
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return null;
            }
        }
        if ((address[10] & 0xff) != 0xff || (address[11] & 0xff) != 0xff) {
            return null;
        }
        byte[] v4 = new byte[4];
        System.arraycopy(address, 12, v4, 0, 4);
        return v4;
    }

    /**
     * 比较地址的前若干位。调用方保证两边等长，且前缀不超过地址位数。
     *
     * @param address 待比较的地址
     * @param network 网段地址
     * @param bits 前缀位数
     * @return 前缀一致时为真
     */
    private static boolean samePrefix(byte[] address, byte[] network, int bits) {
        int full = bits / 8;
        int remainder = bits % 8;
        for (int i = 0; i < full; i++) {
            if (address[i] != network[i]) {
                return false;
            }
        }
        if (remainder == 0) {
            return true;
        }
        int mask = (0xff << (8 - remainder)) & 0xff;
        return (address[full] & mask) == (network[full] & mask);
    }

    /**
     * 名单条目无效。原文可能是误写的凭证，所以不放进异常。
     *
     * @return 固定说明
     */
    private static IllegalStateException invalid() {
        return new IllegalStateException("受信任转发地址必须是 IP 或 CIDR");
    }

    /**
     * 前缀覆盖了全部地址。这种配置会把任意客户端当成 Ingress。
     *
     * @return 固定说明
     */
    private static IllegalStateException tooWide() {
        return new IllegalStateException("受信任转发地址不能覆盖全部地址");
    }

    /**
     * 一条受信任网段。地址按解析后的字节保存，日志不会把它打出来。
     */
    private static final class Rule {
        private final byte[] network;
        private final int prefix;

        /**
         * 记下网段和前缀。前缀已经按地址族收过，不会是 0。
         *
         * @param network 网络地址
         * @param prefix 前缀位数
         */
        private Rule(byte[] network, int prefix) {
            this.network = network;
            this.prefix = prefix;
        }

        /**
         * 地址落在这条网段里时为真。IPv4 映射的 IPv6 可以命中 IPv4 网段。
         *
         * @param address 套接字对端的字节
         * @return 命中时为真
         */
        private boolean matches(byte[] address) {
            byte[] candidate = address;
            if (candidate.length != network.length) {
                byte[] v4 = unmapped(candidate);
                if (v4 == null || network.length != 4) {
                    return false;
                }
                candidate = v4;
            }
            return samePrefix(candidate, network, prefix);
        }
    }
}
