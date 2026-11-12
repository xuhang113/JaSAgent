package dev.jasagent.gateway.auth;

import dev.jasagent.api.auth.AuthService;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * 网关自己的登录、退出、当前用户、健康检查和页面入口。
 * 不提供自助注册。第一个用户仍由本机命令创建，口令规则仍是用户目录里的 PBKDF2。
 * agent 不再提供登录页和控制台，所以这里的页面只跳到 /admin/。
 */
@RestController
public class GatewayLocalController {
    private final AuthService auth;

    /**
     * 注入本机登录服务。本控制器只调用登录、退出和会话查询，不调用建用户。
     *
     * @param auth 用户目录里的登录服务
     */
    public GatewayLocalController(AuthService auth) {
        this.auth = auth;
    }

    /**
     * 用登录名和口令签发会话 Cookie。口令只在这次调用里转成字符数组，用完清掉。
     * 失败时返回 AUTH，正文不包含口令。
     *
     * @param user 登录名
     * @param password 口令
     * @param response 用来写下 Set-Cookie 的响应
     * @return 成功时含登录名；失败时是 401
     */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> login(HttpServletRequest request, HttpServletResponse response) {
        String user = request.getAttribute(LoginFormFilter.USER) instanceof String value ? value : "";
        char[] chars = request.getAttribute(LoginFormFilter.PASSWORD) instanceof char[] value ? value : new char[0];
        try {
            Session session = auth.login(user, chars).toCompletableFuture().join();
            response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(session.token(), -1).toString());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("login", session.login());
            return ResponseEntity.ok(body);
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof AgentException agent && agent.code() == ErrorCode.AUTH) {
                return status(HttpStatus.UNAUTHORIZED, "AUTH");
            }
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL");
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * 注销当前会话并清掉 Cookie。没有会话时也清 Cookie，避免浏览器留下旧令牌。
     *
     * @param request 可能带会话 Cookie 的请求
     * @param response 用来清掉 Cookie 的响应
     * @return 没有正文的 204
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = cookie(request);
        if (token != null && !token.isBlank()) {
            try {
                auth.logout(token).toCompletableFuture().join();
            } catch (RuntimeException ignored) {
                // 会话已经无效时仍然清掉浏览器上的 Cookie。
            }
        }
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString());
        return ResponseEntity.noContent().build();
    }

    /**
     * 返回当前登录名和工作区。工作区还没选时是空串。
     *
     * @param request 已经通过会话过滤的请求
     * @return 含 login 和 workspace 的正文
     */
    @GetMapping("/api/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Session session = (Session) request.getAttribute(GatewaySessionFilter.SESSION);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("login", session.login());
        body.put("workspace", session.workspace() == null ? "" : session.workspace());
        return body;
    }

    /**
     * 网关自己的健康检查。不列出令牌，也不代替 agent 的长连接状态。
     *
     * @return status 为 up
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "up");
    }

    /**
     * 把站点根转到控制台入口 /admin/。
     *
     * @return 302，Location 为 /admin/
     */
    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/admin/").build();
    }

    /**
     * 把旧的登录地址转到控制台登录页。POST /login 仍是接口，不走这里。
     *
     * @return 302，Location 为 /admin/login
     */
    @GetMapping("/login")
    public ResponseEntity<Void> loginPage() {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/admin/login").build();
    }

    /**
     * 做一枚会话 Cookie。maxAge 为 0 时立刻过期。
     *
     * @param value Cookie 值；退出时是空串
     * @param maxAge 存活秒数；-1 表示随浏览器会话
     * @return 只允许 HTTP 读取的 Cookie
     */
    private static ResponseCookie sessionCookie(String value, long maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(GatewaySessionFilter.COOKIE, value)
                .httpOnly(true)
                .path("/")
                .sameSite("Lax");
        if (maxAge >= 0) {
            builder.maxAge(maxAge);
        }
        return builder.build();
    }

    /**
     * 从请求里取出会话 Cookie 的值。没有时为空。
     *
     * @param request 当前请求
     * @return Cookie 值；没有时为空
     */
    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (GatewaySessionFilter.COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 组装不含口令的错误正文。
     *
     * @param status HTTP 状态
     * @param errorCode 错误码
     * @return 带 executionId 和 errorCode 的响应
     */
    private static ResponseEntity<Map<String, Object>> status(HttpStatus status, String errorCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", null);
        body.put("errorCode", errorCode);
        return ResponseEntity.status(status).body(body);
    }
}
