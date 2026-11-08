package dev.jasagent.auth;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.regex.Pattern;

final class LoginNames {
    private static final Pattern LOGIN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private LoginNames() {
    }

    static String require(String login) {
        if (login == null || !LOGIN.matcher(login).matches()) {
            throw new AgentException(ErrorCode.AUTH, "登录名无效");
        }
        return login;
    }
}
