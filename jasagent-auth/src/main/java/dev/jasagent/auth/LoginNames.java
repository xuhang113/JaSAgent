package dev.jasagent.auth;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.util.regex.Pattern;

/**
 * 校验登录名，避免空名、过长名字或路径分隔符被写进用户目录。
 */
final class LoginNames {
    /**
     * 登录名须由字母或数字开头，其后只能是字母、数字、点、下划线或连字符，总长不超过 64 个字符。
     */
    private static final Pattern LOGIN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /**
     * 禁止实例化，登录名只通过静态校验使用。
     */
    private LoginNames() {
    }

    /**
     * 确认登录名能安全用作单个路径分量；空值或不符合模式时拒绝登录或建账。
     *
     * @param login 调用方给出的登录名
     * @return 通过校验的原登录名
     */
    static String require(String login) {
        if (login == null || !LOGIN.matcher(login).matches()) {
            throw new AgentException(ErrorCode.AUTH, "登录名无效");
        }
        return login;
    }
}
