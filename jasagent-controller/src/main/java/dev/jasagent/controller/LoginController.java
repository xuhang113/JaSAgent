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

@RestController
public class LoginController {
    private final AuthService auth;

    public LoginController(AuthService auth) {
        this.auth = auth;
    }

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
