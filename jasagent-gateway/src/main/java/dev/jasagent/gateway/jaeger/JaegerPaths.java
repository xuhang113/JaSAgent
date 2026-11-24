package dev.jasagent.gateway.jaeger;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.Optional;

/**
 * 判断请求是不是 {@code /admin/jaeger} 及其子路径。
 * 编码、反斜杠、点段和空字节都先收成规范路径，避免绕过前缀后落到控制台单页。
 * 规范路径一旦离开这个前缀，就不再当作可以反代的地址。
 */
public final class JaegerPaths {
    /**
     * Jaeger 查询界面的前缀。大小写在比较前会折成小写。
     */
    public static final String PREFIX = "/admin/jaeger";

    /**
     * 禁止实例化。路径规则只通过静态方法使用。
     */
    private JaegerPaths() {
    }

    /**
     * 这条请求的 URI 或 servlet 路径是不是 Jaeger 前缀。任一形态碰上前缀都算。
     *
     * @param request 当前请求
     * @return 必须在网关里拦截时为真
     */
    public static boolean matches(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        return matches(request.getRequestURI()) || matches(combined(request));
    }

    /**
     * 这个原始路径解码、折叠之后是不是 Jaeger 前缀或它的子路径。
     * 点段跳出前缀时仍返回真，调用方因此不能把它交给控制台。
     *
     * @param raw 请求路径，可以带查询串
     * @return 属于这个前缀时为真
     */
    public static boolean matches(String raw) {
        return inspect(raw).matched();
    }

    /**
     * 取出可以反代的规范路径。路径跳出前缀、带空字节，或两种路径形态不一致时为空。
     *
     * @param request 当前请求
     * @return 以 {@link #PREFIX} 开头的路径；不能反代时为空
     */
    public static Optional<String> upstreamPath(HttpServletRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        View uri = inspect(request.getRequestURI());
        View combined = inspect(combined(request));
        if (uri.blocked() || combined.blocked()) {
            return Optional.empty();
        }
        if (uri.path() != null && combined.path() != null && !uri.path().equals(combined.path())) {
            return Optional.empty();
        }
        if (uri.path() != null) {
            return Optional.of(uri.path());
        }
        if (combined.path() != null) {
            return Optional.of(combined.path());
        }
        return Optional.empty();
    }

    /**
     * 取出一条原始路径对应的规范路径。跳出前缀或带空字节时为空。
     *
     * @param raw 请求路径，可以带查询串
     * @return 能反代的路径；只是命中前缀但不能安全转发时为空
     */
    public static Optional<String> upstreamPath(String raw) {
        View view = inspect(raw);
        return view.path() == null ? Optional.empty() : Optional.of(view.path());
    }

    /**
     * 把 servlet 路径和 path info 接成一条路径。容器把解码结果放在这里时也能认出来。
     *
     * @param request 当前请求
     * @return 拼接后的路径；两段都空时是空串
     */
    private static String combined(HttpServletRequest request) {
        String servlet = request.getServletPath() == null ? "" : request.getServletPath();
        String info = request.getPathInfo() == null ? "" : request.getPathInfo();
        return servlet + info;
    }

    /**
     * 看一条原始路径落在前缀内、必须拦住，还是根本无关。
     *
     * @param raw 请求路径
     * @return 分类结果
     */
    private static View inspect(String raw) {
        if (raw == null || raw.isBlank()) {
            return View.miss();
        }
        String prepared = prepare(raw);
        if (prepared.indexOf('\0') >= 0) {
            String before = prepared.substring(0, prepared.indexOf('\0'));
            return touches(before) || touches(prepared.replace("\0", "")) ? View.block() : View.miss();
        }
        String canonical = canonicalize(prepared);
        boolean touched = touches(prepared) || under(canonical);
        if (!touched) {
            return View.miss();
        }
        if (!under(canonical)) {
            return View.block();
        }
        return View.allow(canonical);
    }

    /**
     * 去掉查询串，把反斜杠和过度编码的斜杠折成斜杠，再重复解码并转成小写。
     *
     * @param raw 原始路径
     * @return 折叠后的路径
     */
    private static String prepare(String raw) {
        String value = stripQuery(raw).replace('\\', '/');
        value = value.replaceAll("(?i)%c0%af", "%2f");
        value = value.replaceAll("(?i)%e0%80%af", "%2f");
        String decoded = value;
        for (int i = 0; i < 3; i++) {
            String next = decodeOnce(decoded);
            if (next.equals(decoded)) {
                break;
            }
            decoded = next;
        }
        return collapse(decoded.replace('\\', '/')).toLowerCase(Locale.ROOT);
    }

    /**
     * 去掉每段里的分号参数，再解析 {@code .} 和 {@code ..}。
     *
     * @param prepared 已经解码并折叠过的路径
     * @return 以斜杠开头的规范路径
     */
    private static String canonicalize(String prepared) {
        String[] parts = prepared.split("/", -1);
        StringBuilder stack = new StringBuilder();
        int depth = 0;
        for (String part : parts) {
            int semi = part.indexOf(';');
            if (semi >= 0) {
                part = part.substring(0, semi);
            }
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                int slash = stack.lastIndexOf("/");
                if (depth > 0 && slash >= 0) {
                    stack.setLength(slash);
                    depth--;
                }
                continue;
            }
            stack.append('/').append(part);
            depth++;
        }
        return depth == 0 ? "/" : stack.toString();
    }

    /**
     * 路径在去掉点段之前是不是已经踩在前缀上。用来拦住 {@code /admin/jaeger/../} 这种跳出。
     *
     * @param path 尚未解析点段的路径
     * @return 等于前缀、以前缀加斜杠开头，或前缀后紧跟分号时为真
     */
    private static boolean touches(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String value = collapse(path);
        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return PREFIX.equals(value) || path.startsWith(PREFIX + "/") || path.startsWith(PREFIX + ";") || collapse(path).startsWith(PREFIX + "/");
    }

    /**
     * 规范路径是不是仍在前缀里面。前缀后面必须是结尾或斜杠，避免吃掉别的名字。
     *
     * @param path 规范路径
     * @return 可以反代时为真
     */
    private static boolean under(String path) {
        return PREFIX.equals(path) || (path != null && path.startsWith(PREFIX + "/"));
    }

    /**
     * 去掉查询串和片段。
     *
     * @param raw 原始路径
     * @return 只有路径的部分
     */
    private static String stripQuery(String raw) {
        int query = raw.indexOf('?');
        String value = query >= 0 ? raw.substring(0, query) : raw;
        int hash = value.indexOf('#');
        return hash >= 0 ? value.substring(0, hash) : value;
    }

    /**
     * 把连续斜杠收成一条。
     *
     * @param value 路径
     * @return 折叠后的路径
     */
    private static String collapse(String value) {
        String current = value;
        while (current.contains("//")) {
            current = current.replace("//", "/");
        }
        return current;
    }

    /**
     * 解码一层百分号。{@code +} 保持原样，不把它当成空格。非法十六进制则停止这一处。
     *
     * @param value 可能含百分号的路径
     * @return 解码一层后的路径
     */
    private static String decodeOnce(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '%' && i + 2 < value.length()) {
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.append((char) (hi * 16 + lo));
                    i += 2;
                    continue;
                }
            }
            out.append(current);
        }
        return out.toString();
    }

    /**
     * 一条路径的判定。没命中、必须拦住，或可以反代到某个规范路径。
     *
     * @param matched 原始路径属于这个前缀
     * @param blocked 属于前缀但不能安全反代
     * @param path 可以反代的规范路径；不能反代时为空
     */
    private record View(boolean matched, boolean blocked, String path) {
        /**
         * 和 Jaeger 无关。
         *
         * @return 未命中的结果
         */
        private static View miss() {
            return new View(false, false, null);
        }

        /**
         * 踩到了前缀，但不能把规范路径交出去。
         *
         * @return 拦住的结果
         */
        private static View block() {
            return new View(true, true, null);
        }

        /**
         * 可以反代到这条规范路径。
         *
         * @param path 以 {@link JaegerPaths#PREFIX} 开头的路径
         * @return 放行反代的结果
         */
        private static View allow(String path) {
            return new View(true, false, path);
        }
    }
}
