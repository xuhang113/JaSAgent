package dev.jasagent.controller;

import dev.jasagent.api.runtime.CallerScope;
import dev.jasagent.api.runtime.GatewayHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 只放行带内部令牌的调用，并从请求头读取网关传来的租户、用户和会话。
 * 不再接受登录口令或会话 Cookie。控制台页面一律拒绝，不把静态页交给浏览器。
 */
@Component
@Order(1)
public class SessionAuthFilter extends OncePerRequestFilter {
    private final String expectedToken;

    /**
     * 记下内部令牌。配置为空时再看进程环境变量 JASAGENT_INTERNAL_TOKEN。两者都空则所有业务接口拒绝。
     *
     * @param configured 配置项 jasagent.internal.token，可以是空白
     */
    public SessionAuthFilter(@Value("${jasagent.internal.token:}") String configured) {
        String token = configured == null ? "" : configured.trim();
        if (token.isBlank()) {
            String fromEnv = System.getenv("JASAGENT_INTERNAL_TOKEN");
            token = fromEnv == null ? "" : fromEnv.trim();
        }
        this.expectedToken = token;
    }

    /**
     * 健康检查直接放行。页面和静态资源返回 404。其余请求必须带对得上的内部令牌和用户 id。
     * 副作用：通过时把 {@link CallerScope} 放进请求和当前线程，请求结束后清掉线程上的身份。
     *
     * @param request 当前请求
     * @param response 用来写 401 或 404
     * @param chain 后续过滤器和控制器
     * @throws ServletException 后续过滤器抛出时继续抛出
     * @throws IOException 写响应失败时抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/health".equals(path)) {
            chain.doFilter(request, response);
            return;
        }
        if (page(path)) {
            response.setStatus(404);
            return;
        }
        if (!same(expectedToken, request.getHeader(GatewayHeaders.TOKEN))) {
            reject(response, expectedToken.isBlank() ? "未配置内部令牌" : "内部令牌无效");
            return;
        }
        String userId = request.getHeader(GatewayHeaders.USER);
        if (userId == null || userId.isBlank()) {
            reject(response, "缺少网关用户");
            return;
        }
        CallerScope scope = new CallerScope(
                request.getHeader(GatewayHeaders.TENANT),
                userId,
                request.getHeader(GatewayHeaders.SESSION));
        request.setAttribute(CallerScope.ATTRIBUTE, scope);
        CallerScope.bind(scope);
        try {
            chain.doFilter(request, response);
        } finally {
            CallerScope.clear();
        }
    }

    /**
     * 判断是不是原来的控制台页面或静态资源。这些路径不再提供页面。
     *
     * @param path 请求路径
     * @return 应直接 404 时为 true
     */
    private static boolean page(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        return "/".equals(path) || "/login".equals(path) || "/logout".equals(path) || "/index.html".equals(path)
                || "/run".equals(path) || "/favicon.ico".equals(path)
                || path.startsWith("/assets/") || path.startsWith("/css/") || path.startsWith("/js/")
                || path.startsWith("/executions/");
    }

    /**
     * 用 401 和认证错误码拒绝请求。说明里不包含令牌原文。
     *
     * @param response 当前响应
     * @param message 给调用方的说明
     * @throws IOException 写正文失败时抛出
     */
    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"executionId\":null,\"errorCode\":\"AUTH\",\"message\":\"" + escaped + "\"}");
    }

    /**
     * 比较两段令牌。先做摘要再比较，避免用明文长度提前返回。期望值为空时一律不相等。
     *
     * @param expected 进程里配置的令牌
     * @param given 请求头里的令牌
     * @return 两段都非空且摘要一致时为 true
     */
    private static boolean same(String expected, String given) {
        if (expected == null || expected.isBlank() || given == null || given.isBlank()) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] left = digest.digest(expected.getBytes(StandardCharsets.UTF_8));
            byte[] right = MessageDigest.getInstance("SHA-256").digest(given.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(left, right);
        } catch (Exception ex) {
            return false;
        }
    }
}
