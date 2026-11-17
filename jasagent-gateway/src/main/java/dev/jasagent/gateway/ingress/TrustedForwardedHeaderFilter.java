package dev.jasagent.gateway.ingress;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 在其它过滤器之前处理转发头。
 * 直接连上来的地址不在 {@link TrustedProxyList} 里时，丢掉 X-Forwarded-* 和 Forwarded，
 * 避免客户端自己把协议改成 HTTPS，或把跳转改到别的主机。
 * 地址受信任时，只采用这一跳留下的最右侧 X-Forwarded-Proto / Host / Port / For。
 * 跳转仍由调用方写成相对路径，本过滤器不改 Location。
 */
@Component
@Order(TrustedForwardedHeaderFilter.ORDER)
public class TrustedForwardedHeaderFilter extends OncePerRequestFilter implements Ordered {
    /**
     * 必须先于登录表单和会话过滤器。数值越小越早。
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;
    /**
     * 请求已经过本过滤器。值是布尔真，不含令牌或地址。
     */
    static final String APPLIED = "jasagent.gateway.forwarded.applied";
    /**
     * 受信任时仍保留的转发头。键是小写名，值是写回请求的规范名。其余 X-Forwarded-* 和 Forwarded 丢掉。
     */
    private static final Map<String, String> KEPT_HEADERS = Map.of(
            "x-forwarded-proto", "X-Forwarded-Proto",
            "x-forwarded-host", "X-Forwarded-Host",
            "x-forwarded-port", "X-Forwarded-Port",
            "x-forwarded-for", "X-Forwarded-For");

    private final TrustedProxyList proxies;

    /**
     * 使用启动时解析好的名单。名单为空时每个请求都按不受信任处理。
     *
     * @param proxies 受信任的 Ingress / Funnel 地址
     */
    public TrustedForwardedHeaderFilter(TrustedProxyList proxies) {
        this.proxies = proxies;
    }

    /**
     * 返回固定顺序，保证比登录表单过滤器更早。
     *
     * @return {@link #ORDER}
     */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 按套接字对端决定信任与否，再把处理过的请求交给后面的过滤器和控制器。
     * 不读取正文，也不把转发头的内容写进日志。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param chain 后面的过滤器和控制器
     * @throws ServletException 后续过滤器抛出时继续抛出
     * @throws IOException 后续过滤器抛出时继续抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(APPLIED, Boolean.TRUE);
        if (!proxies.trusts(request.getRemoteAddr())) {
            chain.doFilter(new UntrustedRequest(request), response);
            return;
        }
        chain.doFilter(new TrustedRequest(request, proxies), response);
    }

    /**
     * 把同一个头的多次取值按出现顺序拼起来，方便取最右侧一段。
     *
     * @param request 原始请求，不能是已经摘过头的包装
     * @param name 头名
     * @return 用逗号接起来的原文；没有时为空串
     */
    static String joined(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        while (values.hasMoreElements()) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            String value = values.nextElement();
            if (value != null) {
                builder.append(value);
            }
        }
        return builder.toString();
    }

    /**
     * 这个头是不是客户端可以伪造的转发头。比较时忽略大小写。
     *
     * @param name 头名
     * @return X-Forwarded-* 或 Forwarded 时为真
     */
    static boolean forwardedName(String name) {
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return "forwarded".equals(key) || key.startsWith("x-forwarded-");
    }

    /**
     * 不受信任的请求。转发头从视图里消失，协议和主机保持这条连接自己的值。
     * 受信任包装类会继承这里的摘头逻辑，所以本类不能是 final。
     */
    private static class UntrustedRequest extends HttpServletRequestWrapper {
        /**
         * 包住原始请求。之后的读取都不再看见转发头。
         *
         * @param request 原始请求
         */
        private UntrustedRequest(HttpServletRequest request) {
            super(request);
        }

        /**
         * 转发头视为不存在。其它头原样返回。
         *
         * @param name 头名
         * @return 头的值；被丢掉时为空
         */
        @Override
        public String getHeader(String name) {
            if (forwardedName(name)) {
                return null;
            }
            return super.getHeader(name);
        }

        /**
         * 转发头视为没有取值。
         *
         * @param name 头名
         * @return 取值；被丢掉时为空枚举
         */
        @Override
        public Enumeration<String> getHeaders(String name) {
            if (forwardedName(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }

        /**
         * 头名列表里不再出现转发头。
         *
         * @return 剩下的头名
         */
        @Override
        public Enumeration<String> getHeaderNames() {
            return visibleNames(null);
        }

        /**
         * 转发头不再当成整数。避免端口被客户端写成别的数。
         *
         * @param name 头名
         * @return 整数值；被丢掉时为 -1
         */
        @Override
        public int getIntHeader(String name) {
            if (forwardedName(name)) {
                return -1;
            }
            return super.getIntHeader(name);
        }

        /**
         * 转发头不再当成日期。
         *
         * @param name 头名
         * @return 日期；被丢掉时为 -1
         */
        @Override
        public long getDateHeader(String name) {
            if (forwardedName(name)) {
                return -1;
            }
            return super.getDateHeader(name);
        }

        /**
         * 收集仍然可见的头名。子类把要保留的转发头规范名放进 extra 的值。
         *
         * @param extra 要补回的头名；没有时为空。键不使用，值是规范头名
         * @return 头名枚举
         */
        final Enumeration<String> visibleNames(Map<String, String> extra) {
            List<String> names = new ArrayList<>();
            Enumeration<String> current = super.getHeaderNames();
            if (current != null) {
                while (current.hasMoreElements()) {
                    String name = current.nextElement();
                    if (!forwardedName(name)) {
                        names.add(name);
                    }
                }
            }
            if (extra != null) {
                names.addAll(extra.values());
            }
            return Collections.enumeration(names);
        }
    }

    /**
     * 受信任的这一跳。协议、主机和端口按它声明的最右侧一段覆盖，其余转发头丢掉。
     * 没有声明协议时保留连接自己的协议，这样网关直接终止 TLS 时仍然是 HTTPS。
     */
    private static final class TrustedRequest extends UntrustedRequest {
        private final String scheme;
        private final boolean secure;
        private final String serverName;
        private final int serverPort;
        private final Map<String, String> kept;

        /**
         * 从原始请求读出转发头，收成最右侧一段。只在构造时读一次，避免包装后再读到自己改过的值。
         *
         * @param request 原始请求
         * @param proxies 受信任名单，用来解释协议
         */
        private TrustedRequest(HttpServletRequest request, TrustedProxyList proxies) {
            super(request);
            String declared = proxies.declaredScheme(request.getRemoteAddr(), joined(request, "X-Forwarded-Proto"));
            if ("https".equals(declared)) {
                this.scheme = "https";
                this.secure = true;
            } else if ("http".equals(declared)) {
                this.scheme = "http";
                this.secure = false;
            } else {
                this.scheme = request.getScheme();
                this.secure = request.isSecure();
            }
            HostAndPort host = hostAndPort(TrustedProxyList.rightmost(joined(request, "X-Forwarded-Host")));
            this.serverName = host.name == null ? request.getServerName() : host.name;
            this.serverPort = resolvePort(request, host.port, declared);
            this.kept = keptHeaders(request);
        }

        /**
         * 返回这一跳声明的协议；没有声明时是连接自己的协议。
         *
         * @return http 或 https
         */
        @Override
        public String getScheme() {
            return scheme;
        }

        /**
         * 浏览器面对的是不是 HTTPS。会话 Cookie 的 Secure 只看这个结果。
         *
         * @return 应加 Secure 时为真
         */
        @Override
        public boolean isSecure() {
            return secure;
        }

        /**
         * 返回这一跳声明的主机。没有声明时是连接自己的主机。
         * 网关自己的跳转不读这个值，避免把主机写进 Location。
         *
         * @return 主机名
         */
        @Override
        public String getServerName() {
            return serverName;
        }

        /**
         * 返回这一跳声明的端口。
         *
         * @return 端口
         */
        @Override
        public int getServerPort() {
            return serverPort;
        }

        /**
         * 用认定后的协议、主机和端口拼请求 URL。调用方的 Location 仍应写相对路径。
         *
         * @return 绝对形式的请求 URL，仅供读取，不直接当作跳转
         */
        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer();
            url.append(scheme).append("://").append(serverName);
            boolean defaultPort = ("http".equals(scheme) && serverPort == 80) || ("https".equals(scheme) && serverPort == 443);
            if (serverPort > 0 && !defaultPort) {
                url.append(':').append(serverPort);
            }
            String path = getRequestURI();
            if (path != null) {
                url.append(path);
            }
            return url;
        }

        /**
         * 只返回这一跳自己的那一段。客户端加在前面的值不再可见。
         *
         * @param name 头名
         * @return 最右侧一段；没有时为空
         */
        @Override
        public String getHeader(String name) {
            String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (kept.containsKey(key)) {
                return kept.get(key);
            }
            return super.getHeader(name);
        }

        /**
         * 保留的转发头只有最右侧一段。
         *
         * @param name 头名
         * @return 单值枚举，或父类的结果
         */
        @Override
        public Enumeration<String> getHeaders(String name) {
            String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (kept.containsKey(key)) {
                return Collections.enumeration(List.of(kept.get(key)));
            }
            return super.getHeaders(name);
        }

        /**
         * 头名里用规范写法放回仍然保留的转发头。
         *
         * @return 头名
         */
        @Override
        public Enumeration<String> getHeaderNames() {
            return visibleNames(canonicalKept());
        }

        /**
         * 端口头返回整数。其它保留头不是整数时返回 -1，不把原文抛出去。
         *
         * @param name 头名
         * @return 整数；没有或不是端口时为 -1
         */
        @Override
        public int getIntHeader(String name) {
            String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (kept.containsKey(key)) {
                return portNumber(kept.get(key));
            }
            return super.getIntHeader(name);
        }

        /**
         * 把保留的头收成规范名，供头名枚举使用。
         *
         * @return 小写键到规范名
         */
        private Map<String, String> canonicalKept() {
            Map<String, String> names = new LinkedHashMap<>();
            for (String key : kept.keySet()) {
                names.put(key, KEPT_HEADERS.get(key));
            }
            return names;
        }

        /**
         * 只留下四个转发头的最右侧一段。没有值的头不放进映射。
         *
         * @param request 原始请求
         * @return 小写头名到最右侧一段
         */
        private static Map<String, String> keptHeaders(HttpServletRequest request) {
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : KEPT_HEADERS.entrySet()) {
                String token = TrustedProxyList.rightmost(joined(request, entry.getValue()));
                if (!token.isEmpty()) {
                    values.put(entry.getKey(), token);
                }
            }
            return values;
        }

        /**
         * 决定对外端口。显式端口头优先，其次是主机里的端口，再次按声明的协议用 443 或 80。
         *
         * @param request 原始请求
         * @param hostPort 主机里带的端口；没有时为 -1
         * @param declared 这一跳声明的协议；没有时为空串
         * @return 端口
         */
        private static int resolvePort(HttpServletRequest request, int hostPort, String declared) {
            int headerPort = portNumber(TrustedProxyList.rightmost(joined(request, "X-Forwarded-Port")));
            if (headerPort > 0) {
                return headerPort;
            }
            if (hostPort > 0) {
                return hostPort;
            }
            if ("https".equals(declared)) {
                return 443;
            }
            if ("http".equals(declared)) {
                return 80;
            }
            return request.getServerPort();
        }
    }

    /**
     * 从主机头里拆出主机名和端口。含空格、斜杠或用户信息的值整个丢掉。
     *
     * @param token 最右侧主机
     * @return 拆开的结果；非法时名字为空、端口为 -1
     */
    private static HostAndPort hostAndPort(String token) {
        if (token == null || token.isBlank()) {
            return HostAndPort.NONE;
        }
        String value = token.trim();
        if (value.indexOf(' ') >= 0 || value.indexOf('@') >= 0 || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            return HostAndPort.NONE;
        }
        String name;
        String portText = null;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end <= 1) {
                return HostAndPort.NONE;
            }
            name = value.substring(1, end);
            if (end + 1 < value.length()) {
                if (value.charAt(end + 1) != ':') {
                    return HostAndPort.NONE;
                }
                portText = value.substring(end + 2);
            }
        } else {
            int colon = value.lastIndexOf(':');
            if (colon >= 0 && value.indexOf(':') != colon) {
                return HostAndPort.NONE;
            }
            if (colon >= 0) {
                name = value.substring(0, colon);
                portText = value.substring(colon + 1);
            } else {
                name = value;
            }
        }
        if (!hostNameOk(name)) {
            return HostAndPort.NONE;
        }
        int port = portText == null ? -1 : portNumber(portText);
        if (portText != null && port < 0) {
            return HostAndPort.NONE;
        }
        return new HostAndPort(name, port);
    }

    /**
     * 主机名只能是字面量 IP，或由字母、数字、点和连字符组成的名字。
     *
     * @param name 不含端口的主机
     * @return 可以当成主机名时为真
     */
    private static boolean hostNameOk(String name) {
        if (name == null || name.isEmpty() || name.length() > 253 || name.startsWith(".") || name.endsWith(".") || name.contains("..")) {
            return false;
        }
        if (name.indexOf(':') >= 0) {
            return TrustedProxyList.parseLiteral(name) != null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析 1 到 65535 的端口。不是纯数字时返回 -1，不抛异常。
     *
     * @param text 端口原文
     * @return 端口；非法时为 -1
     */
    private static int portNumber(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
            if (value > 65535) {
                return -1;
            }
        }
        return value == 0 ? -1 : value;
    }

    /**
     * 主机和可选端口。名字为空表示这一跳没有给出可用的主机。
     */
    private static final class HostAndPort {
        /**
         * 没有主机头时使用的空结果。
         */
        private static final HostAndPort NONE = new HostAndPort(null, -1);

        private final String name;
        private final int port;

        /**
         * 记下拆开的主机和端口。
         *
         * @param name 主机名；没有时为空
         * @param port 端口；没有时为 -1
         */
        private HostAndPort(String name, int port) {
            this.name = name;
            this.port = port;
        }
    }
}
