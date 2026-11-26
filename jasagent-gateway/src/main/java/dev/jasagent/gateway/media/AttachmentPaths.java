package dev.jasagent.gateway.media;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 判断请求是不是 {@code /api/sessions/{sessionId}/attachments} 及其子路径。
 * 先解码、折叠斜杠再解析点段。编码、大小写、多余斜杠和 {@code ..} 都不能绕过这个前缀落到 agent 转发。
 * 点段一旦踩进前缀又跳出去，仍然算命中，调用方必须自己处理，不能继续转发。
 */
public final class AttachmentPaths {
    /**
     * 折叠前的路径里，附件前缀是否已经出现。用来拦住 {@code /attachments/../messages} 这种跳出。
     */
    private static final Pattern TOUCH = Pattern.compile("(?i)(^|/)api/sessions/[^/]+/attachments($|/|;)");
    /**
     * 会话编号和附件编号的最大长度，与表列宽一致。
     */
    private static final int ID_LIMIT = 64;
    /**
     * 标识的单个路径分量。点段和斜杠不在其中。
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * 禁止实例化。路径规则只通过静态方法使用。
     */
    private AttachmentPaths() {
    }

    /**
     * 这条路径规范化之后是不是附件前缀，包括点段跳出后仍必须由网关拦住的情况。
     *
     * @param path 请求路径，可以带查询串
     * @return 不能交给 agent 转发时为真
     */
    public static boolean intercepts(String path) {
        return inspect(path).kind() != Kind.MISS;
    }

    /**
     * 看一条原始路径落在附件前缀的哪一种结果上。
     *
     * @param raw 请求路径，可以带查询串
     * @return 未命中、拦住、集合、单条或多余段
     */
    public static Target inspect(String raw) {
        if (raw == null || raw.isBlank()) {
            return Target.miss();
        }
        String prepared = prepare(raw);
        if (prepared.indexOf('\0') >= 0) {
            String before = prepared.substring(0, prepared.indexOf('\0'));
            String stripped = prepared.replace("\0", "");
            if (classify(before).kind() != Kind.MISS || classify(stripped).kind() != Kind.MISS) {
                return Target.block();
            }
            return Target.miss();
        }
        return classify(prepared);
    }

    /**
     * 同时看请求 URI 和 servlet 路径。任一种被拦住，或两种落点不一致，都不能转发。
     *
     * @param request 当前请求
     * @return 附件请求的落点；与附件无关时为未命中
     */
    public static Target resolve(HttpServletRequest request) {
        if (request == null) {
            return Target.miss();
        }
        Target uri = inspect(request.getRequestURI());
        Target combined = inspect(combine(request));
        if (uri.kind() == Kind.MISS && combined.kind() == Kind.MISS) {
            return Target.miss();
        }
        if (uri.kind() == Kind.BLOCKED || combined.kind() == Kind.BLOCKED) {
            return Target.block();
        }
        if (uri.kind() != Kind.MISS && combined.kind() != Kind.MISS && !same(uri, combined)) {
            return Target.block();
        }
        return uri.kind() != Kind.MISS ? uri : combined;
    }

    /**
     * 去掉查询串和片段，把反斜杠和过度编码的斜杠折成斜杠，再重复解码。
     *
     * @param raw 原始路径
     * @return 折叠后、尚未解析点段的路径
     */
    private static String prepare(String raw) {
        String decoded = stripQuery(raw).replace('\\', '/');
        for (int i = 0; i < 3; i++) {
            String next = decodeOnce(expandSlash(decoded)).replace('\\', '/');
            if (next.equals(decoded)) {
                break;
            }
            decoded = next;
        }
        return collapse(decoded);
    }

    /**
     * 解析点段。曾经进入附件前缀、但规范路径已经离开时，结果是拦住而不是未命中。
     *
     * @param prepared 已解码并折叠的路径
     * @return 分类结果
     */
    private static Target classify(String prepared) {
        boolean ever = touches(prepared);
        List<String> stack = new ArrayList<>();
        for (String raw : prepared.split("/", -1)) {
            String part = raw;
            int semi = part.indexOf(';');
            if (semi >= 0) {
                part = part.substring(0, semi);
            }
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (under(stack)) {
                    ever = true;
                }
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            stack.add(part);
            if (under(stack)) {
                ever = true;
            }
        }
        if (under(stack)) {
            return allow(stack);
        }
        if (ever) {
            return Target.block();
        }
        return Target.miss();
    }

    /**
     * 规范路径仍在附件前缀里时，按段数分成集合、单条或多余段。标识不合法则拦住。
     *
     * @param stack 已经解析过点段的路径段，固定段不区分大小写
     * @return 可处理或必须拦住的结果
     */
    private static Target allow(List<String> stack) {
        String sessionId = stack.get(2);
        if (!safeId(sessionId)) {
            return Target.block();
        }
        if (stack.size() == 4) {
            return Target.collection(sessionId);
        }
        if (stack.size() == 5) {
            String attachmentId = stack.get(4);
            if (!safeId(attachmentId)) {
                return Target.block();
            }
            return Target.item(sessionId, attachmentId);
        }
        return Target.extra(sessionId);
    }

    /**
     * 当前栈是不是已经处在某一场会话的附件前缀上。
     *
     * @param stack 路径段
     * @return 第 1、2、4 段分别是 api、sessions、attachments 时为真
     */
    private static boolean under(List<String> stack) {
        return stack.size() >= 4
                && eq(stack.get(0), "api")
                && eq(stack.get(1), "sessions")
                && eq(stack.get(3), "attachments");
    }

    /**
     * 折叠前的路径是否已经写出附件前缀。
     *
     * @param prepared 尚未解析点段的路径
     * @return 出现前缀时为真
     */
    private static boolean touches(String prepared) {
        return prepared != null && TOUCH.matcher(prepared).find();
    }

    /**
     * 会话或附件编号能否当作一个路径分量。
     *
     * @param value 候选编号
     * @return 长度、字符和点段都合法时为真
     */
    private static boolean safeId(String value) {
        return value != null && value.length() <= ID_LIMIT && !value.contains("..") && SAFE_ID.matcher(value).matches();
    }

    /**
     * 路径段是否等于期望的固定名字。比较时忽略大小写。
     *
     * @param part 实际路径段
     * @param expected 固定名字
     * @return 忽略大小写后相等时为真
     */
    private static boolean eq(String part, String expected) {
        return part != null && part.equalsIgnoreCase(expected);
    }

    /**
     * 两种路径形态是不是同一个落点。
     *
     * @param left 请求 URI 的结果
     * @param right servlet 路径的结果
     * @return 种类和编号都一致时为真
     */
    private static boolean same(Target left, Target right) {
        return left.kind() == right.kind()
                && Objects.equals(left.sessionId(), right.sessionId())
                && Objects.equals(left.attachmentId(), right.attachmentId());
    }

    /**
     * 把 servlet 路径和 path info 接成一条路径。
     *
     * @param request 当前请求
     * @return 拼接后的路径；两段都空时是空串
     */
    private static String combine(HttpServletRequest request) {
        String servlet = request.getServletPath() == null ? "" : request.getServletPath();
        String info = request.getPathInfo() == null ? "" : request.getPathInfo();
        if (servlet.isEmpty() && info.isEmpty()) {
            return "";
        }
        return servlet + info;
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
     * 把过度编码的斜杠先收成普通百分号斜杠，避免解码后仍留下另一条斜杠。
     *
     * @param value 可能含百分号的路径
     * @return 替换后的路径
     */
    private static String expandSlash(String value) {
        String current = value.replaceAll("(?i)%c0%af", "%2f");
        return current.replaceAll("(?i)%e0%80%af", "%2f");
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
     * 附件路径的落点。未命中的请求才可以继续走原来的转发。
     */
    public enum Kind {
        /**
         * 和附件前缀无关。
         */
        MISS,
        /**
         * 踩到了前缀，但不能当成一次正常的上传或下载。
         */
        BLOCKED,
        /**
         * 附件集合，没有附件编号。
         */
        COLLECTION,
        /**
         * 某一条附件。
         */
        ITEM,
        /**
         * 仍在前缀里，但多出了不认识的段。
         */
        EXTRA
    }

    /**
     * 一条路径的判定。
     *
     * @param kind 落点种类
     * @param sessionId 会话编号；未命中或拦住时为空
     * @param attachmentId 附件编号；集合和拦住时为空
     */
    public record Target(Kind kind, String sessionId, String attachmentId) {
        /**
         * 和附件无关。
         *
         * @return 未命中
         */
        public static Target miss() {
            return new Target(Kind.MISS, null, null);
        }

        /**
         * 必须由网关拦住，不能转发。
         *
         * @return 拦住的结果
         */
        public static Target block() {
            return new Target(Kind.BLOCKED, null, null);
        }

        /**
         * 这一场会话的附件集合。
         *
         * @param sessionId 会话编号
         * @return 集合落点
         */
        public static Target collection(String sessionId) {
            return new Target(Kind.COLLECTION, sessionId, null);
        }

        /**
         * 这一场会话里的一条附件。
         *
         * @param sessionId 会话编号
         * @param attachmentId 附件编号
         * @return 单条落点
         */
        public static Target item(String sessionId, String attachmentId) {
            return new Target(Kind.ITEM, sessionId, attachmentId);
        }

        /**
         * 前缀里面多出来的段。网关回答，不转发。
         *
         * @param sessionId 会话编号
         * @return 多余段落点
         */
        public static Target extra(String sessionId) {
            return new Target(Kind.EXTRA, sessionId, null);
        }
    }
}
