package dev.jasagent.gateway.auth;

import dev.jasagent.api.auth.AuthService;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.AccountDirectory;
import dev.jasagent.auth.AccountProfile;
import dev.jasagent.gateway.media.AttachmentPaths;
import dev.jasagent.gateway.route.GatewayPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;

/**
 * 网关自己的会话过滤。未登录只放行健康检查、登录、注册、退出、登录页、注册页和登录页静态资源。
 * 控制台业务页和 /api 必须带本网关签发的会话。校验用的是 MySQL 里的登录会话，不把口令或令牌写进日志。
 * 登录会话在 MySQL。存活探测按网关副本写入同一库并跨副本读取。本机缺省副本数仍是 1。
 * 未登录页面用相对 Location 转到 /admin/login，不把主机或协议写进跳转。
 */
@Component
@Order(GatewaySessionFilter.ORDER)
public class GatewaySessionFilter extends OncePerRequestFilter implements Ordered {
    /**
     * 在转发头和登录表单之后核对会话。数值越小越早。
     */
    public static final int ORDER = 1;
    /**
     * 登录 Cookie 的名字，和原来的控制台一致。值是会话令牌，只在网关和浏览器之间使用。
     */
    public static final String COOKIE = "JASAGENT_SESSION";
    /**
     * 请求属性名。后续控制器和转发从这里取当前用户。
     */
    public static final String SESSION = "jasagent.session";
    /**
     * 请求属性名。值是当前账号的角色和租户，集群过滤和粘性转发都读这里。
     */
    public static final String ACCOUNT = "jasagent.account";

    private final AuthService auth;

    /**
     * 注入登录服务。它按令牌从 MySQL 换回会话；实现了账号目录时再放上角色和租户。
     *
     * @param auth 按令牌换回会话
     */
    public GatewaySessionFilter(AuthService auth) {
        this.auth = auth;
    }

    /**
     * 放行健康检查、登录、注册、退出、登录页、注册页和登录页静态资源。
     * 必须有会话的 /api，以及规范化后仍落在附件前缀上的路径，没有会话时返回 401，errorCode 为 AUTH。
     * 大小写或编码不同的附件路径也按接口拒绝，不改去登录页。
     * 必须有会话的页面没有会话时转到 /admin/login，不返回控制台业务正文。
     * Location 保持相对路径，不调用会拼出绝对地址的 sendRedirect。
     *
     * @param request 当前请求
     * @param response 用来写 401 或重定向
     * @param chain 后续过滤器和控制器
     * @throws ServletException 后续过滤器抛出时继续抛出
     * @throws IOException 写响应失败时抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        if (anonymous(method, path)) {
            chain.doFilter(request, response);
            return;
        }
        Session session = authenticate(request);
        if (session == null) {
            if (requiresApiAuth(path)) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"executionId\":null,\"errorCode\":\"AUTH\"}");
                return;
            }
            redirectToLogin(response);
            return;
        }
        if (auth instanceof AccountDirectory accounts) {
            AccountProfile profile;
            try {
                profile = accounts.profile(session.login());
            } catch (RuntimeException ex) {
                if (requiresApiAuth(path)) {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write("{\"executionId\":null,\"errorCode\":\"AUTH\"}");
                    return;
                }
                redirectToLogin(response);
                return;
            }
            request.setAttribute(ACCOUNT, profile);
        }
        request.setAttribute(SESSION, session);
        chain.doFilter(request, response);
    }

    /**
     * 晚于转发头过滤器，早于具体控制器。
     *
     * @return {@link #ORDER}
     */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 这条路径没有会话时要按接口回 401，而不是转到登录页。
     * 普通 {@code /api/} 保持原样。附件前缀再按规范化结果判断，避免大小写或编码绕过。
     *
     * @param path 请求路径
     * @return 应按 AUTH 拒绝时为真
     */
    private static boolean requiresApiAuth(String path) {
        return path != null && (path.startsWith("/api/") || AttachmentPaths.intercepts(path));
    }

    /**
     * 这些路径不要求已经登录。规则与 {@link GatewayPaths#anonymous} 相同，避免过滤器和路由各写一份。
     *
     * @param method 请求方法
     * @param path 请求路径
     * @return 可以匿名访问时为真
     */
    private static boolean anonymous(HttpMethod method, String path) {
        return GatewayPaths.anonymous(method, path);
    }

    /**
     * 从 Authorization 或会话 Cookie 取出令牌并核对。核对失败当作没有会话，不记录令牌。
     *
     * @param request 当前请求
     * @return 会话；没有或无效时为空
     */
    private Session authenticate(HttpServletRequest request) {
        String token = token(request);
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return auth.authenticate(token).toCompletableFuture().join();
        } catch (CompletionException ex) {
            return null;
        }
    }

    /**
     * 先读 Bearer 令牌，没有再读会话 Cookie。两种都没有时为空。
     *
     * @param request 当前请求
     * @return 会话令牌；没有时为空
     */
    private static String token(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 把未登录的页面转到 /admin/login。直接写相对 Location，不调用 sendRedirect。
     * sendRedirect 会按容器地址或转发头拼成绝对 URL，本机就会变成 http://127.0.0.1。
     *
     * @param response 当前响应
     */
    private static void redirectToLogin(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, GatewayPaths.ADMIN_LOGIN);
    }
}
