package dev.jasagent.gateway.media;

import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.AccountProfile;
import dev.jasagent.gateway.auth.GatewaySessionFilter;
import dev.jasagent.persist.SessionAttachmentLedger;
import dev.jasagent.persist.SessionLedger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 自己处理会话附件的上传、下载、列表、删除和这个前缀下的其它路径。
 * 不把请求交给后续转发。登录账号来自会话过滤器放在请求上的 MySQL 会话，不在本进程另存一份。
 * 读写都同时带上租户、会话和所有者。下载不改登录 Cookie，也不把令牌放进查询串。
 */
@Component
@Order(AttachmentFilter.ORDER)
public class AttachmentFilter extends OncePerRequestFilter implements Ordered {
    /**
     * 晚于会话、集群和 Jaeger 过滤。数值越小越早。
     */
    public static final int ORDER = 4;

    private final SessionLedger sessions;
    private final SessionAttachmentLedger attachments;

    /**
     * 记下会话账本和附件账本。两者都指向已有的 jasagent 库。
     *
     * @param sessions 用来确认这场会话属于当前用户
     * @param attachments 附件元数据和正文
     */
    public AttachmentFilter(SessionLedger sessions, SessionAttachmentLedger attachments) {
        this.sessions = sessions;
        this.attachments = attachments;
    }

    /**
     * 晚于会话过滤，这样请求上已经有 MySQL 会话。
     *
     * @return {@link #ORDER}
     */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 附件前缀由这里写完响应。其它路径继续往后走，不读正文。
     * 未登录回 401，不是所有者回 403，两种都不带附件正文。
     *
     * @param request 当前请求
     * @param response 用来写 JSON 或附件正文
     * @param chain 后续过滤器和转发；附件路径不会调用它
     * @throws ServletException 后续过滤器抛出时继续抛出
     * @throws IOException 读写正文失败时抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AttachmentPaths.Target target = AttachmentPaths.resolve(request);
        if (target.kind() == AttachmentPaths.Kind.MISS) {
            chain.doFilter(request, response);
            return;
        }
        AccountProfile profile = caller(request);
        if (profile == null) {
            json(response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH");
            return;
        }
        if (target.kind() == AttachmentPaths.Kind.BLOCKED) {
            json(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND");
            return;
        }
        boolean owned;
        try {
            owned = owned(target.sessionId(), profile.tenantId(), profile.login());
        } catch (RuntimeException ex) {
            json(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "INTERNAL");
            return;
        }
        if (!owned) {
            json(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN");
            return;
        }
        try {
            if (target.kind() == AttachmentPaths.Kind.EXTRA) {
                json(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND");
                return;
            }
            if (target.kind() == AttachmentPaths.Kind.COLLECTION) {
                collection(request, response, profile, target.sessionId());
                return;
            }
            item(request, response, profile, target.sessionId(), target.attachmentId());
        } catch (RuntimeException ex) {
            json(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "INTERNAL");
        }
    }

    /**
     * 集合上只接受列出和上传。其它方法不转发。
     *
     * @param request 当前请求
     * @param response 响应
     * @param profile 当前账号
     * @param sessionId 会话编号
     * @throws IOException 读写正文失败时抛出
     */
    private void collection(HttpServletRequest request, HttpServletResponse response, AccountProfile profile, String sessionId)
            throws IOException {
        if ("GET".equals(request.getMethod())) {
            List<SessionAttachmentLedger.Summary> rows = attachments.list(profile.tenantId(), sessionId, profile.login());
            write(response, HttpServletResponse.SC_OK, listJson(rows));
            return;
        }
        if ("POST".equals(request.getMethod())) {
            upload(request, response, profile, sessionId);
            return;
        }
        json(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD");
    }

    /**
     * 单条上只接受下载和删除。其它方法不转发。
     *
     * @param request 当前请求
     * @param response 响应
     * @param profile 当前账号
     * @param sessionId 会话编号
     * @param attachmentId 附件编号
     * @throws IOException 写正文失败时抛出
     */
    private void item(HttpServletRequest request, HttpServletResponse response, AccountProfile profile, String sessionId, String attachmentId)
            throws IOException {
        if ("GET".equals(request.getMethod())) {
            download(response, profile, sessionId, attachmentId);
            return;
        }
        if ("DELETE".equals(request.getMethod())) {
            boolean removed = attachments.delete(profile.tenantId(), sessionId, profile.login(), attachmentId);
            if (!removed) {
                json(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND");
                return;
            }
            response.reset();
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        json(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD");
    }

    /**
     * 读入唯一一份附件，按文件头和原始字节决定收不收。拒绝时不写库。
     * 查询串里的令牌不参与判断。客户端申报的类型只在和探测结果不一致时被忽略。
     *
     * @param request 当前请求
     * @param response 响应
     * @param profile 当前账号
     * @param sessionId 会话编号
     * @throws IOException 读请求体失败时抛出
     */
    private void upload(HttpServletRequest request, HttpServletResponse response, AccountProfile profile, String sessionId)
            throws IOException {
        Loaded loaded = load(request);
        AttachmentBody.Parsed parsed = AttachmentBody.parse(request.getContentType(), loaded.bytes(), loaded.truncated());
        if (parsed.tooLarge()) {
            json(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "PAYLOAD_TOO_LARGE");
            return;
        }
        if (parsed.bad()) {
            json(response, HttpServletResponse.SC_BAD_REQUEST, "BAD_REQUEST");
            return;
        }
        AttachmentMedia.Decision decision = AttachmentMedia.decide(parsed.content());
        if (decision.tooLarge()) {
            json(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "PAYLOAD_TOO_LARGE");
            return;
        }
        if (!decision.accepted()) {
            json(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA");
            return;
        }
        SessionAttachmentLedger.Stored stored = attachments.insert(
                profile.tenantId(), sessionId, profile.login(), decision.mediaType(), parsed.filename(), parsed.content());
        write(response, HttpServletResponse.SC_CREATED, itemJson(
                stored.attachmentId(), stored.sessionId(), stored.mediaType(), stored.byteSize(), stored.displayName()));
    }

    /**
     * 按四列取出正文并写回。没有这一行时 404，不凭附件编号单独查找。
     * 响应带 nosniff，不写登录 Cookie。
     *
     * @param response 响应
     * @param profile 当前账号
     * @param sessionId 会话编号
     * @param attachmentId 附件编号
     * @throws IOException 写正文失败时抛出
     */
    private void download(HttpServletResponse response, AccountProfile profile, String sessionId, String attachmentId)
            throws IOException {
        SessionAttachmentLedger.Stored stored = attachments.find(profile.tenantId(), sessionId, profile.login(), attachmentId)
                .orElse(null);
        if (stored == null) {
            json(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND");
            return;
        }
        byte[] content = stored.content() == null ? new byte[0] : stored.content();
        response.reset();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Content-Type", stored.mediaType());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "private");
        response.setHeader("Content-Disposition", disposition(stored.displayName()));
        response.setContentLength(content.length);
        response.getOutputStream().write(content);
    }

    /**
     * 库里有一场会话，且租户和登录名都对得上。空账本也拒绝，避免把附件挂到还不是自己的会话上。
     * admin 不另开旁路。
     *
     * @param sessionId 会话编号
     * @param tenantId 当前租户
     * @param userId 当前登录名
     * @return 三者一致时为真
     */
    private boolean owned(String sessionId, String tenantId, String userId) {
        for (SessionLedger.Row row : sessions.findBySessionId(sessionId)) {
            if (tenantId.equals(row.tenantId()) && userId.equals(row.userId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取出会话过滤器放上的账号。登录名和租户缺一，或和会话登录名不一致时为空。
     * 不读取查询串里的令牌。
     *
     * @param request 当前请求
     * @return 当前账号；还没有 MySQL 会话时为空
     */
    private static AccountProfile caller(HttpServletRequest request) {
        if (!(request.getAttribute(GatewaySessionFilter.SESSION) instanceof Session session)) {
            return null;
        }
        if (!(request.getAttribute(GatewaySessionFilter.ACCOUNT) instanceof AccountProfile profile)) {
            return null;
        }
        if (profile.login().isBlank() || profile.tenantId().isBlank() || !profile.login().equals(session.login())) {
            return null;
        }
        return profile;
    }

    /**
     * 读请求体，但不超过 {@link AttachmentMedia#READ_CAP}。超出的部分不保留，调用方不得落库。
     *
     * @param request 当前请求
     * @return 已读字节，以及是否被截断
     * @throws IOException 读取失败时抛出
     */
    private static Loaded load(HttpServletRequest request) throws IOException {
        InputStream in = request.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int read = in.read(buffer);
            if (read < 0) {
                return new Loaded(out.toByteArray(), false);
            }
            if (total > AttachmentMedia.READ_CAP - read) {
                return new Loaded(new byte[0], true);
            }
            out.write(buffer, 0, read);
            total += read;
        }
    }

    /**
     * 写一条 JSON 错误。正文只有执行编号和错误码，不带异常文本或附件字节。
     *
     * @param response 响应
     * @param status HTTP 状态
     * @param errorCode 固定错误码
     * @throws IOException 写出失败时抛出
     */
    private static void json(HttpServletResponse response, int status, String errorCode) throws IOException {
        write(response, status, "{\"executionId\":null,\"errorCode\":\"" + errorCode + "\"}");
    }

    /**
     * 先清掉已有响应头，避免把登录 Cookie 写回去，再写 JSON。
     *
     * @param response 响应
     * @param status HTTP 状态
     * @param body JSON 正文
     * @throws IOException 写出失败时抛出
     */
    private static void write(HttpServletResponse response, int status, String body) throws IOException {
        response.reset();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    /**
     * 把摘要列成 JSON 数组。不含正文，也不含对象路径。
     *
     * @param rows 当前用户这场会话的摘要
     * @return JSON 数组
     */
    private static String listJson(List<SessionAttachmentLedger.Summary> rows) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            SessionAttachmentLedger.Summary row = rows.get(i);
            out.append(itemJson(row.attachmentId(), row.sessionId(), row.mediaType(), row.byteSize(), row.displayName()));
        }
        return out.append(']').toString();
    }

    /**
     * 一条附件的 JSON。字段只有编号、会话、类型、大小和展示名。
     *
     * @param attachmentId 附件编号
     * @param sessionId 会话编号
     * @param mediaType 探测到的类型
     * @param byteSize 原始字节数
     * @param name 展示名
     * @return 一个 JSON 对象
     */
    private static String itemJson(String attachmentId, String sessionId, String mediaType, long byteSize, String name) {
        return "{\"attachmentId\":" + quote(attachmentId)
                + ",\"sessionId\":" + quote(sessionId)
                + ",\"mediaType\":" + quote(mediaType)
                + ",\"byteSize\":" + byteSize
                + ",\"name\":" + quote(name)
                + "}";
    }

    /**
     * 把字符串收成 JSON 字面量。反斜杠、引号和控制字符都不原样写出。
     *
     * @param value 字段值
     * @return 带引号的 JSON 字符串
     */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char current = value.charAt(i);
                if (current == '\\' || current == '"') {
                    out.append('\\').append(current);
                } else if (current < 0x20) {
                    out.append(' ');
                } else {
                    out.append(current);
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * 下载用的 Content-Disposition。展示名已经去掉路径和引号；空名用固定的 attachment。
     *
     * @param name 展示名
     * @return inline 头的值
     */
    private static String disposition(String name) {
        String safe = name == null || name.isBlank() ? "attachment" : name;
        return "inline; filename=\"" + safe + "\"";
    }

    /**
     * 一次读请求体的结果。
     *
     * @param bytes 未超过上限时的正文；被截断时是空数组
     * @param truncated 是否已经超过读取上限
     */
    private record Loaded(byte[] bytes, boolean truncated) {
    }
}
