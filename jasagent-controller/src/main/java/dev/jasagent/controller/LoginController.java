package dev.jasagent.controller;

import dev.jasagent.api.auth.AuthService;
import dev.jasagent.api.auth.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 登录时签发只允许 HTTP 读取的会话 Cookie，退出时注销令牌并清掉同一块 Cookie。 */
@RestController
public class LoginController {
    private final AuthService auth;

    /**
     * 注入登录服务。
     *
     * @param auth 校验账号并签发、注销会话
     */
    public LoginController(AuthService auth) {
        this.auth = auth;
    }

    /**
     * 用账号和口令登录，把令牌写进名为 JASAGENT_SESSION 的 Cookie，并返回登录名。
     *
     * @param user 登录名
     * @param password 口令，只在这次调用里转成字符数组
     * @param response 用来写下 Set-Cookie 的响应
     * @return 含登录名的 200 正文
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestParam("user") String user, @RequestParam("password") String password, HttpServletResponse response) {
        Session session = auth.login(user, password.toCharArray()).toCompletableFuture().join();
        ResponseCookie cookie = ResponseCookie.from("JASAGENT_SESSION", session.token())
                .httpOnly(true)
                .path("/")
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.ok(Map.of("login", session.login()));
    }

    /**
     * 请求上有会话则注销其令牌，并写下立刻过期的同名 Cookie。没有会话时只清 Cookie。
     *
     * @param request 可能带当前会话的请求
     * @param response 用来清掉 Cookie 的响应
     * @return 没有正文的 204 响应
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        Session current = (Session) request.getAttribute("jasagent.session");
        if (current != null) {
            auth.logout(current.token()).toCompletableFuture().join();
        }
        ResponseCookie cookie = ResponseCookie.from("JASAGENT_SESSION", "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.noContent().build();
    }
}
